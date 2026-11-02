package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.ForwardWithTunnelDto;
import com.admin.common.dto.ForwardSyncSummary;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.service.ForwardPortReservationService;
import com.admin.common.service.UserIngressAddressService;
import com.admin.common.task.ForwardSyncTaskService;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.TcpPingDiagnosisParser;
import com.admin.common.utils.WebSocketServer;
import com.admin.common.utils.UserDiagnosisView;
import com.admin.common.utils.TunnelForwardDiagnosisLimiter;
import com.admin.entity.*;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * <p>
 * 端口转发服务实现类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class ForwardServiceImpl extends ServiceImpl<ForwardMapper, Forward> implements ForwardService {
    @Resource private com.admin.common.service.UserProtocolPolicyService userProtocolPolicyService;
    @Resource
    private com.admin.common.service.UserTunnelAliasService userTunnelAliasService;

    // 常量定义
    private static final String GOST_SUCCESS_MSG = "OK";
    private static final String GOST_NOT_FOUND_MSG = "not found";
    private static final int ADMIN_ROLE_ID = 0;
    private static final int TUNNEL_TYPE_PORT_FORWARD = 1;
    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2;
    private static final int FORWARD_STATUS_ACTIVE = 1;
    private static final int FORWARD_STATUS_PAUSED = 0;
    private static final int FORWARD_STATUS_ERROR = -1;
    private static final int TUNNEL_STATUS_ACTIVE = 1;
    private static final int USER_STATUS_ACTIVE = 1;

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserService userService;

    @Resource
    NodeService nodeService;

    @Resource
    TunnelEntryNodeMapper tunnelEntryNodeMapper;

    @Resource
    @Lazy
    private ForwardSyncTaskService forwardSyncTaskService;

    @Resource
    private ForwardPortReservationService forwardPortReservationService;

    @Resource
    private UserIngressAddressService userIngressAddressService;

    @Resource
    private VpsHostService vpsHostService;

    @Resource
    private com.admin.common.service.ForwardCreationService forwardCreationService;

    @Resource private TunnelForwardDiagnosisLimiter diagnosisLimiter;


    @Override
    public R createForward(ForwardDto forwardDto) {
        final String requestHash;
        try { requestHash = com.admin.common.utils.ForwardCreateRequest.fingerprint(forwardDto); }
        catch (IllegalArgumentException exception) { return R.err(exception.getMessage()); }
        try { GostUtil.normalizeProxyProtocol(forwardDto.getProxyProtocol()); }
        catch (IllegalArgumentException exception) { return R.err(exception.getMessage()); }
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        R previous = forwardCreationService.findExisting(currentUser.getUserId(), forwardDto.getRequestId(), requestHash);
        if (previous != null) return previous;

        // 2. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法创建转发");
        }

        // 3. 普通用户权限和限制检查
        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            R concurrent = forwardCreationService.findExisting(currentUser.getUserId(), forwardDto.getRequestId(), requestHash);
            if (concurrent != null) return concurrent;
            return R.err(permissionResult.getErrorMessage());
        }

        R vpsAccess = validateVpsHostAccess(currentUser, forwardDto.getVpsHostId());
        if (vpsAccess.getCode() != 0) {
            return vpsAccess;
        }
        NodeInfo requiredNodes = getRequiredNodes(tunnel);
        if (requiredNodes.isHasError()) return R.err(requiredNodes.getErrorMessage());

        // The availability query is only an optimization. The reservation row
        // below is the authoritative database race check, so simultaneous
        // requests cannot both configure the same node port.
        String lastReservationError = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            PortAllocation portAllocation = allocatePorts(tunnel, forwardDto.getInPort());
            if (portAllocation.isHasError()) {
                R concurrent = forwardCreationService.findExisting(currentUser.getUserId(), forwardDto.getRequestId(), requestHash);
                if (concurrent != null) return concurrent;
                return R.err(portAllocation.getErrorMessage());
            }

            Forward forward = createForwardEntity(forwardDto, currentUser, portAllocation);
            forward.setCreateRequestId(forwardDto.getRequestId());
            forward.setCreateRequestHash(requestHash);
            try {
                return forwardCreationService.createQueued(forward);
            } catch (com.admin.common.service.ForwardCreationService.Rejected rejected) {
                lastReservationError = rejected.getMessage();
                if (!rejected.isPortConflict() || forwardDto.getInPort() != null) return R.err(lastReservationError);
            }
        }
        return R.err(lastReservationError == null ? "端口已被并发占用，请重试" : lastReservationError);
    }

    @Override
    public R getAllForwards() {
        UserInfo currentUser = getCurrentUserInfo();

        List<ForwardWithTunnelDto> forwardList;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            forwardList = baseMapper.selectForwardsWithTunnelByUserId(currentUser.getUserId());
        } else {
            forwardList = baseMapper.selectAllForwardsWithTunnel();
        }

        Map<Long, ForwardSyncSummary> syncSummaries = forwardSyncTaskService.activeSummaries(
                forwardList.stream().map(ForwardWithTunnelDto::getId).collect(Collectors.toList()));
        for (ForwardWithTunnelDto forward : forwardList) {
            ForwardSyncSummary summary = syncSummaries.get(forward.getId());
            if (summary == null) continue;
            forward.setSyncOperation(summary.getOperation());
            forward.setSyncState(summary.getRetriedTasks() != null && summary.getRetriedTasks() > 0
                    ? "partial" : "syncing");
            forward.setSyncError(summary.getLastError());
        }

        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            Map<Integer, UserIngressAddressService.Address> addresses = userIngressAddressService.forUser(currentUser.getUserId());
            for (ForwardWithTunnelDto forward : forwardList) {
                UserIngressAddressService.Address address = addresses.getOrDefault(
                        forward.getTunnelId(), UserIngressAddressService.UNASSIGNED);
                forward.setInIp(address.address());
                forward.setEntryDomain(address.domain());
                forward.setEntryAddressMode(address.mode());
                forward.setInterfaceName(null);
                // Agent errors can embed private node addresses or credentials.
                if (forward.getSyncError() != null) forward.setSyncError("节点同步尚未完成，请联系管理员检查");
            }
        }

        return R.ok(forwardList);
    }

    @Override
    public R updateForward(ForwardUpdateDto forwardUpdateDto) {
        try { GostUtil.normalizeProxyProtocol(forwardUpdateDto.getProxyProtocol()); }
        catch (IllegalArgumentException exception) { return R.err(exception.getMessage()); }
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward existForward = validateForwardExists(forwardUpdateDto.getId(), currentUser);
        if (existForward == null) {
            return R.err("转发不存在");
        }

        // 3. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardUpdateDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法更新转发");
        }
        boolean tunnelChanged = isTunnelChanged(existForward, forwardUpdateDto);

        // 4. Every save recreates the GOST service, even when its tunnel does
        // not change. Recheck the forward owner's effective permission on every
        // edit so an expired, disabled or exhausted assignment cannot be
        // revived by changing an unrelated field. The result also carries the
        // speed limiter that must be preserved on an in-place update.
        UserPermissionResult permissionResult = checkForwardOwnerPermissions(
                currentUser, existForward, tunnel, forwardUpdateDto.getId());
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }

        Long nextVpsHostId = resolveVpsHostId(forwardUpdateDto, existForward);
        R vpsAccess = validateUpdatedForwardVpsHost(existForward, nextVpsHostId);
        if (vpsAccess.getCode() != 0) {
            return vpsAccess;
        }

        // 5. Keep the existing service-name convention for administrator-owned
        // forwards while using the validated assignment for regular users.
        UserTunnel userTunnel = permissionResult.getUserTunnel();
        if (userTunnel == null) {
            userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
        }

        // 6. 更新Forward对象
        Forward updatedForward = updateForwardEntity(forwardUpdateDto, existForward, tunnel);
        updatedForward.setVpsHostId(nextVpsHostId);
        boolean portLayoutChanged = tunnelChanged
                || !Objects.equals(updatedForward.getInPort(), existForward.getInPort())
                || !Object…40522 tokens truncated…         <Card key={userGroup.userId || 'unknown'} className="shadow-sm border border-divider w-full overflow-hidden">
                  <CardHeader className="pb-3">
                    <div className="flex items-center justify-between w-full min-w-0">
                      <div className="flex items-center gap-3 min-w-0 flex-1">
                        <div className="w-10 h-10 bg-primary-100 dark:bg-primary-900/30 rounded-full flex items-center justify-center flex-shrink-0">
                          <svg className="w-5 h-5 text-primary" fill="currentColor" viewBox="0 0 20 20">
                            <path fillRule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clipRule="evenodd" />
                          </svg>
                        </div>
                        <div className="min-w-0 flex-1">
                          <h2 className="text-base font-medium text-foreground truncate max-w-[150px] sm:max-w-[250px] md:max-w-[350px] lg:max-w-[450px]">{userGroup.userName}</h2>
                          <p className="text-xs text-default-500 truncate max-w-[150px] sm:max-w-[250px] md:max-w-[350px] lg:max-w-[450px]">
                            {userGroup.tunnelGroups.length} 个隧道，
                            {userGroup.tunnelGroups.reduce((total, tg) => total + tg.forwards.length, 0)} 个转发
                          </p>
                        </div>
                      </div>
                      <Chip color="primary" variant="flat" size="sm" className="text-xs flex-shrink-0 ml-2">
                        创建 / 归属用户
                      </Chip>
                    </div>
                  </CardHeader>
                  
                  <CardBody className="pt-0">
                    <Accordion variant="splitted" className="px-0">
                      {userGroup.tunnelGroups.map((tunnelGroup) => (
                        <AccordionItem
                          key={tunnelGroup.tunnelId}
                          aria-label={tunnelGroup.tunnelName}
                          title={
                            <div className="flex items-center justify-between w-full min-w-0 pr-4">
                              <div className="flex items-center gap-3 min-w-0 flex-1">
                                <div className="w-8 h-8 bg-success-100 dark:bg-success-900/30 rounded-lg flex items-center justify-center flex-shrink-0">
                                  <svg className="w-4 h-4 text-success" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                                  </svg>
                                </div>
                                <div className="min-w-0 flex-1">
                                  <h3 className="text-sm font-medium text-foreground truncate max-w-[120px] sm:max-w-[200px] md:max-w-[300px] lg:max-w-[400px]">{tunnelGroup.tunnelName}</h3>
                                </div>
                              </div>
                              <div className="flex items-center gap-2 flex-shrink-0 ml-2">
                                <Chip variant="flat" size="sm" className="text-xs">
                                  {tunnelGroup.forwards.filter(f => f.serviceRunning).length}/{tunnelGroup.forwards.length}
                                </Chip>
                              </div>
                            </div>
                          }
                          className="shadow-none border border-divider"
                        >
                          <div className="flex flex-col gap-2 border-b border-divider px-4 pb-3 pt-1 sm:flex-row sm:items-center sm:justify-between">
                            <p className="text-xs text-default-500">检测全部转发的分段 TCP 连通性；每 20 条自动分批，30 秒冷却</p>
                            <Button
                              size="sm"
                              color="primary"
                              variant="flat"
                              className="min-h-9 flex-shrink-0"
                              onPress={() => handleDiagnoseTunnelForwards(tunnelGroup)}
                            >
                              一键 PING
                            </Button>
                          </div>
                          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4 p-4">
                            {tunnelGroup.forwards.map((forward) => renderForwardCard(forward, undefined))}
                          </div>
                        </AccordionItem>
                      ))}
                    </Accordion>
                  </CardBody>
                </Card>
              ))}
            </div>
          ) : (
            /* 空状态 */
            <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
              <CardBody className="text-center py-16">
                <div className="flex flex-col items-center gap-4">
                  <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                    <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 9l4-4 4 4m0 6l-4 4-4-4" />
                    </svg>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-foreground">暂无转发配置</h3>
                    <p className="text-default-500 text-sm mt-1">还没有创建任何转发配置，点击上方按钮开始创建</p>
                  </div>
                </div>
              </CardBody>
            </Card>
          )
        ) : (
          /* 直接显示模式 */
          forwards.length > 0 ? (
            <>
              {!isAdministrator && userGroups.flatMap((group) => group.tunnelGroups).length > 0 && (
                <Card className="mb-4 border border-primary-200 bg-primary-50/50 shadow-none dark:border-primary-300/20 dark:bg-primary-100/10">
                  <CardBody className="gap-3 p-3 sm:flex-row sm:items-center sm:justify-between">
                    <p className="text-sm text-default-700 dark:text-default-300">按隧道检测全部转发链路</p>
                    <div className="flex flex-wrap gap-2">
                      {userGroups.flatMap((group) => group.tunnelGroups).map((tunnelGroup) => (
                        <Button
                          key={tunnelGroup.tunnelId}
                          size="sm"
                          color="primary"
                          variant="flat"
                          onPress={() => handleDiagnoseTunnelForwards(tunnelGroup)}
                        >
                          {tunnelGroup.tunnelName} · 一键 PING
                        </Button>
                      ))}
                    </div>
                  </CardBody>
                </Card>
              )}
              <DndContext
                sensors={sensors}
                collisionDetection={closestCenter}
                onDragEnd={handleDragEnd}
                onDragStart={() => {}} // 添加空的 onDragStart 处理器
              >
                <SortableContext
                  items={getSortedForwards().map(f => f.id || 0).filter(id => id > 0)}
                  strategy={rectSortingStrategy}
                >
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4">
                    {getSortedForwards().map((forward) => (
                      forward && forward.id ? (
                        <SortableForwardCard key={forward.id} forward={forward} />
                      ) : null
                    ))}
                  </div>
                </SortableContext>
              </DndContext>
            </>
          ) : (
            /* 空状态 */
            <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
              <CardBody className="text-center py-16">
                <div className="flex flex-col items-center gap-4">
                  <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                    <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 9l4-4 4 4m0 6l-4 4-4-4" />
                    </svg>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-foreground">暂无转发配置</h3>
                    <p className="text-default-500 text-sm mt-1">还没有创建任何转发配置，点击上方按钮开始创建</p>
                  </div>
                </div>
              </CardBody>
            </Card>
          )
        )}

        <Modal isOpen={bulkModalOpen} onOpenChange={open => {
          if (!bulkApplying && !bulkPreviewLoading) setBulkModalOpen(open);
        }} isDismissable={!bulkApplying && !bulkPreviewLoading} isKeyboardDismissDisabled={bulkApplying || bulkPreviewLoading} hideCloseButton={bulkApplying || bulkPreviewLoading} size="5xl" scrollBehavior="inside" placement="center">
          <ModalContent>
            <ModalHeader className="flex flex-col gap-1">
              <span>选择性批量修改 · {bulkSelectedIds.size} 条规则</span>
              <span className="text-sm font-normal text-default-500">勾选要修改的字段，预览后应用；未勾选字段、隧道、端口、归属及 VPS 关联保持原值。</span>
            </ModalHeader>
            <ModalBody>
              <Alert color="warning" variant="flat">
                当前更新接口会重建所选规则的节点服务，可能中断这些规则的现有连接。检查时已暂停、异常或同步中的规则会跳过；分条执行且没有整体回滚。执行期间请避免在其他页面同时编辑或暂停这些规则。
              </Alert>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="md:col-span-2 space-y-2">
                  <Switch size="sm" isSelected={bulkFields.remoteAddr} isDisabled={bulkApplying || bulkPreviewLoading} onValueChange={enabled => setBulkField('remoteAddr', enabled)}>修改目标地址</Switch>
                  <Textarea aria-label="批量目标地址" placeholder="每行一个 IP / 域名:端口，IPv6 使用 [地址]:端口" value={bulkValues.remoteAddr} isDisabled={!bulkFields.remoteAddr || bulkApplying || bulkPreviewLoading} onValueChange={value => setBulkValue('remoteAddr', value)} variant="bordered" minRows={2} />
                </div>
                <div className="space-y-2">
                  <Switch size="sm" isSelected={bulkFields.interfaceName} isDisabled={bulkApplying || bulkPreviewLoading} onValueChange={enabled => setBulkField('interfaceName', enabled)}>修改出口接口 / IP</Switch>
                  <Input aria-label="批量出口接口或 IP" placeholder="勾选后留空可清除此字段" value={bulkValues.interfaceName} isDisabled={!bulkFields.interfaceName || bulkApplying || bulkPreviewLoading} onValueChange={value => setBulkValue('interfaceName', value)} variant="bordered" />
                </div>
                <div className="space-y-2">
                  <Switch size="sm" isSelected={bulkFields.strategy} isDisabled={bulkApplying || bulkPreviewLoading} onValueChange={enabled => setBulkField('strategy', enabled)}>修改负载策略</Switch>
                  <Select aria-label="批量负载策略" selectedKeys={[bulkValues.strategy]} isDisabled={!bulkFields.strategy || bulkApplying || bulkPreviewLoading} onSelectionChange={keys => {
                    const key = Array.from(keys)[0];
                    if (key) setBulkValue('strategy', String(key));
                  }} variant="bordered">
                    <SelectItem key="fifo">主备模式</SelectItem>
                    <SelectItem key="round">轮询模式</SelectItem>
                    <SelectItem key="rand">随机模式</SelectItem>
                    <SelectItem key="hash">IP 哈希模式</SelectItem>
                  </Select>
                </div>
                <div className="md:col-span-2 space-y-2">
                  <Switch size="sm" isSelected={bulkFields.proxyProtocol} isDisabled={bulkApplying || bulkPreviewLoading} onValueChange={enabled => setBulkField('proxyProtocol', enabled)}>修改发送 Proxy Protocol</Switch>
                  <Select aria-label="批量发送 Proxy Protocol" selectedKeys={[bulkValues.proxyProtocol]} isDisabled={!bulkFields.proxyProtocol || bulkApplying || bulkPreviewLoading} disallowEmptySelection onSelectionChange={keys => {
                    const key = Array.from(keys)[0];
                    if (key !== undefined) setBulkValue('proxyProtocol', String(key));
                  }} variant="bordered" description={PROXY_PROTOCOL_DESCRIPTION}>
                    <SelectItem key="0">关闭</SelectItem>
                    <SelectItem key="1">PROXY v1</SelectItem>
                    <SelectItem key="2">PROXY v2</SelectItem>
                  </Select>
                </div>
              </div>
              {bulkPlan.length > 0 && (
                <div className="space-y-3 mt-2" aria-live="polite">
                  <p className="text-sm">预览：{bulkPlan.filter(entry => entry.state === 'ready').length} 条可应用，{bulkPlan.filter(entry => entry.state === 'skipped').length} 条跳过。应用前会再次检查最新配置；冲突规则需重新预览。</p>
                  {bulkPlan.map(entry => {
                    const result = bulkResults[entry.id];
                    const state = result?.state || entry.state;
                    const color = state === 'success' ? 'success' : state === 'failed' || state === 'conflict' ? 'danger' : state === 'applying' ? 'primary' : 'default';
                    const label = { ready: '待应用', skipped: '跳过', applying: '执行中', success: '成功', failed: '失败', conflict: '冲突' }[state];
                    const displayValue = (field: BulkForwardField, value: string) => field === 'proxyProtocol' ? proxyProtocolLabel(value) : field === 'strategy' ? (({ fifo: '主备', round: '轮询', rand: '随机', hash: 'IP 哈希' } as Record<string, string>)[value] || value) : value || '（空）';
                    return (
                      <div key={entry.id} className="rounded-lg border border-divider p-3 space-y-2">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-sm font-medium break-all">{entry.name} <span className="text-default-400">#{entry.id}</span></span>
                          <div className="flex items-center gap-2">
                            <Chip size="sm" variant="flat" color={color}>{label}</Chip>
                            {state === 'failed' && <Button size="sm" color="warning" variant="flat" isDisabled={bulkApplying || bulkPreviewLoading} onPress={() => void applyBulkChanges(entry.id)}>重试此条</Button>}
                          </div>
                        </div>
                        {entry.changes.map(change => (
                          <div key={change.field} className="grid grid-cols-1 sm:grid-cols-[7rem_1fr_1fr] gap-2 text-xs">
                            <span className="text-default-500">{bulkForwardFieldLabels[change.field]}</span>
                            <div className="break-all whitespace-pre-wrap"><span className="text-default-500">原值：</span>{displayValue(change.field, change.before)}</div>
                            <div className="break-all whitespace-pre-wrap text-primary"><span className="text-default-500">新值：</span>{displayValue(change.field, change.after)}</div>
                          </div>
                        ))}
                        <p className={`text-xs break-all ${state === 'failed' || state === 'conflict' ? 'text-danger' : 'text-default-500'}`}>{result?.message || entry.message}</p>
                      </div>
                    );
                  })}
                </div>
              )}
            </ModalBody>
            <ModalFooter>
              <Button variant="light" isDisabled={bulkApplying || bulkPreviewLoading} onPress={() => setBulkModalOpen(false)}>关闭</Button>
              <Button variant="flat" color="secondary" isLoading={bulkPreviewLoading} isDisabled={bulkApplying || !Object.values(bulkFields).some(Boolean)} onPress={() => void previewBulkChanges()}>预览修改</Button>
              <Button color="primary" isLoading={bulkApplying} isDisabled={bulkPreviewLoading || !bulkPlan.some(entry => entry.state === 'ready' && !bulkResults[entry.id])} onPress={() => void applyBulkChanges()}>应用预览中的修改</Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 新增/编辑模态框 */}
        <Modal 
          isOpen={modalOpen}
          onOpenChange={open => { if (!submitBusyRef.current) setModalOpen(open); }}
          isDismissable={!submitLoading}
          isKeyboardDismissDisabled={submitLoading}
          size="2xl"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">
                    {isEdit ? '编辑转发' : '新增转发'}
                  </h2>
                  <p className="text-small text-default-500">
                    {isEdit ? '修改现有转发配置的信息' : '创建新的转发配置'}
                  </p>
                </ModalHeader>
                <ModalBody>
                  <div className="space-y-4 pb-4">
                    <Input
                      label="转发名称"
                      placeholder="请输入转发名称"
                      value={form.name}
                      onChange={(e) => setForm(prev => ({ ...prev, name: e.target.value }))}
                      isInvalid={!!errors.name}
                      errorMessage={errors.name}
                      variant="bordered"
                    />
                    
                    <Select
                      label="关联托管 VPS（可选）"
                      placeholder="选择目标服务所在的 VPS"
                      selectedKeys={form.vpsHostId !== null ? [String(form.vpsHostId)] : ['0']}
                      onSelectionChange={(keys) => {
                        if (keys === 'all') return;
                        const selectedKey = Array.from(keys)[0];
                        if (selectedKey === undefined) return;
                        if (String(selectedKey) === '0') {
                          setForm((previous) => ({
                            ...previous,
                            vpsHostId: null,
                            clearVpsHost: true,
                            vpsHostName: undefined,
                            vpsHostOrigin: undefined,
                          }));
                          return;
                        }
                        const host = vpsOptions.find((item) => String(item.id) === String(selectedKey));
                        if (!host) return;
                        setForm((previous) => ({
                          ...previous,
                          vpsHostId: host.id,
                          clearVpsHost: false,
                          vpsHostName: host.name.replace('（历史关联）', ''),
                          vpsHostOrigin: host.origin,
                          remoteAddr: previous.remoteAddr.trim() ? previous.remoteAddr : formatVpsTargetAddress(host),
                        }));
                      }}
                      variant="bordered"
                      description={
                        selectedVpsHost
                          ? `已关联 ${selectedVpsHost.name}；请在目标地址中补全该 VPS 上服务的端口。`
                          : form.vpsHostId !== null
                            ? '该 VPS 已从托管列表移除，历史关联会保留；可改选可用 VPS 或选择不关联。'
                            : '可选择自己托管或管理员分配的 VPS。关联会记录在转发中，管理员可统一查看和管理。'
                      }
                    >
                      {vpsOptions.map((host) => (
                        <SelectItem key={String(host.id)} textValue={host.id === 0 ? host.name : `${host.name} · ${vpsOriginLabel(host.origin)}`}>
                          {host.id === 0 ? host.name : <>{host.name} · {vpsOriginLabel(host.origin)}{host.host ? ` · ${host.host}` : ''}</>}
                        </SelectItem>
                      ))}
                    </Select>

                    <Select
                      label={isAdministrator ? '选择隧道' : '选择已授权隧道'}
                      placeholder="请选择关联的隧道"
                      selectedKeys={form.tunnelId !== null ? [String(form.tunnelId)] : []}
                      onSelectionChange={(keys) => {
                        if (keys !== 'all') {
                          const selectedKey = Array.from(keys)[0];
                          if (selectedKey !== undefined) {
                            handleTunnelChange(String(selectedKey));
                          }
                        }
                      }}
                      isInvalid={!!errors.tunnelId}
                      errorMessage={errors.tunnelId}
                      variant="bordered"
                      description={
                        isAdministrator
                          ? '管理员可管理任意隧道；保存时仍会按照该转发归属用户的权限、配额和限速进行校验。'
                          : '这里只显示管理员已分配给你的隧道；入口地址、端口范围、流量配额和限速均按该授权执行。'
                      }
                    >
                      {tunnels.map((tunnel) => (
                        <SelectItem key={String(tunnel.id)} textValue={`${tunnel.name} · ${assignedEntryAddress(tunnel, isAdministrator) || UNASSIGNED_ENTRY_ADDRESS}`}>
                          {tunnel.name} · {assignedEntryAddress(tunnel, isAdministrator) || UNASSIGNED_ENTRY_ADDRESS}
                        </SelectItem>
                      ))}
                    </Select>

                    {selectedTunnel && (
                      <Input
                        label="你的入口地址"
                        value={assignedEntryAddress(selectedTunnel, isAdministrator) || UNASSIGNED_ENTRY_ADDRESS}
                        isReadOnly
                        variant="flat"
                        description={
                          !assignedEntryAddress(selectedTunnel, isAdministrator)
                            ? '请联系管理员检查该隧道的入口域名分配。'
                            : selectedTunnel.entryAddressMode === 'CUSTOM'
                            ? '管理员为你指定的解析域名；新建后的转发会使用同一入口端口。'
                            : selectedTunnel.entryAddressMode === 'DEFAULT'
                              ? '该隧道的默认解析域名；新建后的转发会使用同一入口端口。'
                              : '使用当前授权允许的入口地址。'
                        }
                      />
                    )}
                    
                    <Input
                      label="入口端口"
                      placeholder="留空随机分配"
                      type="number"
                      value={form.inPort?.toString() || ''}
                      onChange={(e) => setForm(prev => ({ 
                        ...prev, 
                        inPort: e.target.value ? parseInt(e.target.value) : null 
                      }))}
                      isInvalid={!!errors.inPort}
                      errorMessage={errors.inPort}
                      variant="bordered"
                      description={
                        selectedTunnel && selectedTunnel.inNodePortSta && selectedTunnel.inNodePortEnd
                          ? `允许范围: ${selectedTunnel.inNodePortSta}-${selectedTunnel.inNodePortEnd}`
                          : '留空将从节点端口范围随机分配未占用端口'
                      }
                    />
                    
                    <Textarea
                      label={selectedVpsHost ? `目标地址 · ${selectedVpsHost.name}` : '目标地址'}
                      placeholder="请输入远程地址，多个地址用换行分隔&#10;例如:&#10;192.168.1.100:8080&#10;example.com:3000"
                      value={form.remoteAddr}
                      onChange={(e) => setForm(prev => ({ ...prev, remoteAddr: e.target.value }))}
                      isInvalid={!!errors.remoteAddr}
                      errorMessage={errors.remoteAddr}
                      variant="bordered"
                      description={
                        selectedVpsHost
                          ? `已关联 ${selectedVpsHost.name}：填写该 VPS 上实际服务的 IP/域名和端口；支持多个地址（每行一个）。`
                          : '格式: IP:端口 或 域名:端口，支持多个地址（每行一个）'
                      }
                      minRows={3}
                      maxRows={6}
                    />
                    
                    <details className="console-advanced-options">
                      <summary>高级设置 <span>负载均衡 · 出口网卡 · Proxy Protocol</span></summary>
                      <div className="space-y-4 pt-4">
                    <div className="rounded-lg border border-divider p-3 space-y-3">
                      <Switch
                        isSelected={form.proxyProtocol !== 0}
                        onValueChange={enabled => setForm(previous => ({ ...previous, proxyProtocol: enabled ? 1 : 0 }))}
                        isDisabled={submitLoading}
                        size="sm"
                      >发送 Proxy Protocol</Switch>
                      <p className="text-xs text-default-500">{PROXY_PROTOCOL_DESCRIPTION}</p>
                      {form.proxyProtocol !== 0 && (
                        <Select
                          label="Proxy Protocol 版本"
                          selectedKeys={[String(form.proxyProtocol)]}
                          disallowEmptySelection
                          isDisabled={submitLoading}
                          onSelectionChange={keys => {
                            const key = Array.from(keys)[0];
                            if (key === '1' || key === '2') setForm(previous => ({ ...previous, proxyProtocol: parseProxyProtocol(key) }));
                          }}
                          variant="bordered"
                        >
                          <SelectItem key="1">PROXY v1 · 文本格式</SelectItem>
                          <SelectItem key="2">PROXY v2 · 二进制格式</SelectItem>
                        </Select>
                      )}
                    </div>

                    <Input
                      label="出口网卡名或IP"
                      placeholder="请输入出口网卡名或IP"
                      value={form.interfaceName}
                      onChange={(e) => setForm(prev => ({ ...prev, interfaceName: e.target.value }))}
                      isInvalid={!!errors.interfaceName}
                      errorMessage={errors.interfaceName}
                      variant="bordered"
                      description="用于多IP服务器指定使用那个IP请求远程地址，不懂的默认为空就行"
                    />
                    
                    {getAddressCount(form.remoteAddr) > 1 && (
                      <Select
                        label="负载策略"
                        placeholder="请选择负载均衡策略"
                        selectedKeys={[form.strategy]}
                        onSelectionChange={(keys) => {
                          const selectedKey = Array.from(keys)[0] as string;
                          setForm(prev => ({ ...prev, strategy: selectedKey }));
                        }}
                        variant="bordered"
                        description="多个目标地址的负载均衡策略"
                      >
                        <SelectItem key="fifo" >主备模式 - 自上而下</SelectItem>
                        <SelectItem key="round" >轮询模式 - 依次轮换</SelectItem>
                        <SelectItem key="rand" >随机模式 - 随机选择</SelectItem>
                        <SelectItem key="hash" >哈希模式 - IP哈希</SelectItem>
                      </Select>
                    )}
                      </div>
                    </details>
                  </div>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button 
                    color="primary" 
                    onPress={handleSubmit}
                    isLoading={submitLoading}
                  >
                    {isEdit ? '保存修改' : '创建转发'}
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 删除确认模态框 */}
        <Modal 
          isOpen={deleteModalOpen}
          onOpenChange={setDeleteModalOpen}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-lg font-bold text-danger">确认删除</h2>
                </ModalHeader>
                <ModalBody>
                  <p className="text-default-600">
                    确定要删除转发 <span className="font-semibold text-foreground">"{forwardToDelete?.name}"</span> 吗？
                  </p>
                  <p className="text-small text-default-500 mt-2">
                    此操作无法撤销，删除后该转发将永久消失。
                  </p>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button 
                    color="danger" 
                    onPress={confirmDelete}
                    isLoading={deleteLoading}
                  >
                    确认删除
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 地址列表弹窗 */}
        <Modal isOpen={addressModalOpen} onClose={() => setAddressModalOpen(false)} size="lg" scrollBehavior="outside">
          <ModalContent>
            <ModalHeader className="text-base">{addressModalTitle}</ModalHeader>
            <ModalBody className="pb-6">
              <div className="mb-4 text-right">
                <Button size="sm" onClick={copyAllAddresses}>
                  复制
                </Button>
              </div>
              
              <div className="space-y-2 max-h-60 overflow-y-auto">
                {addressList.map((item) => (
                  <div key={item.id} className="flex justify-between items-center p-3 border border-default-200 dark:border-default-100 rounded-lg">
                    <code className="text-sm flex-1 mr-3 text-foreground">{item.address}</code>
                    <Button
                      size="sm"
                      variant="light"
                      isLoading={item.copying}
                      onClick={() => copyAddress(item)}
                    >
                      复制
                    </Button>
                  </div>
                ))}
              </div>
            </ModalBody>
          </ModalContent>
        </Modal>

        {/* 导出数据模态框 */}
        <Modal 
          isOpen={exportModalOpen} 
          onClose={() => {
            setExportModalOpen(false);
            setSelectedTunnelForExport(null);
            setExportData('');
          }} 
          
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader className="flex flex-col gap-1">
              <h2 className="text-xl font-bold">导出转发数据</h2>
              <p className="text-small text-default-500">
                v2 JSON：保留目标地址、名称、入口端口、出口接口、负载策略和 Proxy Protocol。
              </p>
            </ModalHeader>
            <ModalBody className="pb-6">
              <div className="space-y-4">
                <Select
                  label="导出范围"
                  selectedKeys={[exportScope]}
                  onSelectionChange={keys => {
                    const scope = Array.from(keys)[0];
                    if (scope === 'tunnel' || scope === 'filtered') setExportScope(scope);
                  }}
                  disallowEmptySelection
                  variant="bordered"
                >
                  <SelectItem key="tunnel">整条隧道</SelectItem>
                  <SelectItem key="filtered">当前筛选结果（全部分页）</SelectItem>
                </Select>
                {/* 隧道选择 */}
                {exportScope === 'tunnel' && <div>
                  <Select
                    label="选择导出隧道"
                    placeholder="请选择要导出的隧道"
                    selectedKeys={selectedTunnelForExport ? [selectedTunnelForExport.toString()] : []}
                    onSelectionChange={(keys) => {
                      const selectedKey = Array.from(keys)[0] as string;
                      setSelectedTunnelForExport(selectedKey ? parseInt(selectedKey) : null);
                    }}
                    variant="bordered"
                    isRequired
                  >
                    {tunnels.map((tunnel) => (
                      <SelectItem key={tunnel.id.toString()} textValue={tunnel.name}>
                        {tunnel.name}
                      </SelectItem>
                    ))}
                  </Select>
                </div>}

                <p className="text-small text-default-600" role="status">
                  {exportScope === 'filtered'
                    ? `当前筛选结果 ${exportCandidateCount} 条，包含所有分页。`
                    : selectedTunnelForExport ? `所选隧道共 ${exportCandidateCount} 条，不受列表搜索或状态筛选影响。` : '请选择要导出的隧道。'}
                </p>
                <p className="text-tiny text-default-500">导入时将在所选隧道创建规则；VPS 绑定和暂停状态不随文件迁移。</p>

                {/* 导出按钮和数据 */}
                {exportData && (
                  <div className="flex flex-wrap gap-2 items-center">
                    <Button 
                      color="primary" 
                      size="sm" 
                      onPress={executeExport}
                      isLoading={exportLoading}
                      isDisabled={exportCandidateCount === 0}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
                        </svg>
                      }
                    >
                      重新生成
                    </Button>
                    <Button 
                      color="secondary" 
                      size="sm" 
                      onPress={copyExportData}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path d="M8 3a1 1 0 011-1h2a1 1 0 110 2H9a1 1 0 01-1-1z" />
                          <path d="M6 3a2 2 0 00-2 2v11a2 2 0 002 2h8a2 2 0 002-2V5a2 2 0 00-2-2 3 3 0 01-3 3H9a3 3 0 01-3-3z" />
                        </svg>
                      }
                    >
                      复制
                    </Button>
                    <Button color="primary" variant="flat" size="sm" onPress={downloadExportData} startContent={<ConsoleIcon name="download" size={16}/>}>下载 JSON</Button>
                    <span className="text-tiny text-default-500">已生成 {exportedCount} 条</span>
                  </div>
                )}

                {/* 初始导出按钮 */}
                {!exportData && (
                  <div className="text-right">
                    <Button 
                      color="primary" 
                      size="sm" 
                      onPress={executeExport}
                      isLoading={exportLoading}
                      isDisabled={exportCandidateCount === 0}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
                        </svg>
                      }
                    >
                      生成导出数据
                    </Button>
                  </div>
                )}

                {/* 导出数据显示 */}
                {exportData && (
                  <div className="relative">
                    <Textarea
                      aria-label="转发导出 JSON"
                      value={exportData}
                      readOnly
                      variant="bordered"
                      minRows={10}
                      maxRows={20}
                      className="font-mono text-sm"
                      classNames={{
                        input: "font-mono text-sm"
                      }}
                      placeholder="暂无数据"
                    />
                  </div>
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button 
                variant="light" 
                onPress={() => setExportModalOpen(false)}
              >
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 导入数据模态框 */}
        <Modal 
          isOpen={importModalOpen} 
          onClose={() => setImportModalOpen(false)} 
          
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader className="flex flex-col gap-1">
              <h2 className="text-xl font-bold">导入转发数据</h2>
              <p className="text-small text-default-500">
                推荐使用本面板导出的 v2 JSON 文件；仍兼容旧格式：目标地址|转发名称|入口端口，每行一个
              </p>
              <p className="text-small text-default-400">
                v2 会保留出口接口、负载策略和 Proxy Protocol；旧记录未指定的出口接口保持自动选择，Proxy Protocol 默认关闭。旧格式的第 4 列仍会忽略。目标地址支持域名/IPv4:端口及 [IPv6]:端口，多个地址可用逗号分隔；重复记录会被安全跳过。
              </p>
            </ModalHeader>
            <ModalBody className="pb-6">
              <div className="space-y-4">
                {/* 隧道选择 */}
                <div>
                  <Select
                    label="选择导入隧道"
                    placeholder="请选择要导入的隧道"
                    selectedKeys={selectedTunnelForImport ? [selectedTunnelForImport.toString()] : []}
                    onSelectionChange={(keys) => {
                      const selectedKey = Array.from(keys)[0] as string;
                      setSelectedTunnelForImport(selectedKey ? parseInt(selectedKey) : null);
                    }}
                    variant="bordered"
                    isRequired
                  >
                    {tunnels.map((tunnel) => (
                      <SelectItem key={tunnel.id.toString()} textValue={tunnel.name}>
                        {tunnel.name}
                      </SelectItem>
                    ))}
                  </Select>
                </div>

                {/* 输入区域 */}
                <div>
                  <Textarea
                    label="导入数据"
                    placeholder="粘贴 v2 JSON，或旧格式：目标地址|转发名称|入口端口"
                    value={importData}
                    onChange={(e) => setImportData(e.target.value)}
                    variant="flat"
                    minRows={8}
                    maxRows={12}
                    classNames={{
                      input: "font-mono text-sm"
                    }}
                  />

                
                </div>

                {/* 导入结果 */}
                {importResults.length > 0 && (
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <h3 className="text-base font-semibold">导入结果</h3>
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-default-500">
                          成功：{importResults.filter(r => r.success).length} / 
                          总计：{importResults.length}
                        </span>
                      </div>
                    </div>
                    
                    <div className="max-h-40 overflow-y-auto space-y-1" style={{
                      scrollbarWidth: 'thin',
                      scrollbarColor: 'rgb(156 163 175) transparent'
                    }}>
                      {importResults.map((result, index) => (
                        <div 
                          key={index} 
                          className={`p-2 rounded border ${
                            result.success 
                              ? 'bg-success-50 dark:bg-success-100/10 border-success-200 dark:border-success-300/20' 
                              : 'bg-danger-50 dark:bg-danger-100/10 border-danger-200 dark:border-danger-300/20'
                          }`}
                        >
                          <div className="flex items-center gap-2">
                            {result.success ? (
                              <svg className="w-3 h-3 text-success-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                              </svg>
                            ) : (
                              <svg className="w-3 h-3 text-danger-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
                              </svg>
                            )}
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 mb-0.5">
                                <span className={`text-xs font-medium ${
                                  result.success ? 'text-success-700 dark:text-success-300' : 'text-danger-700 dark:text-danger-300'
                                }`}>
                                  {result.success ? '成功' : '失败'}
                                </span>
                                <span className="text-xs text-default-500">|</span>
                                <code className="text-xs font-mono text-default-600 truncate">{result.line}</code>
                              </div>
                              <div className={`text-xs ${
                                result.success ? 'text-success-600 dark:text-success-400' : 'text-danger-600 dark:text-danger-400'
                              }`}>
                                {result.message}
                              </div>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button 
                variant="light" 
                onPress={() => setImportModalOpen(false)}
              >
                关闭
              </Button>
              <Button 
                color="warning" 
                onPress={executeImport}
                isLoading={importLoading}
                isDisabled={!importData.trim() || !selectedTunnelForImport}
              >
                开始导入
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 隧道一键 PING 结果：只从具体隧道的展开区发起 */}
        <Modal
          isOpen={tunnelPingModalOpen}
          onOpenChange={(open) => {
            if (open) {
              setTunnelPingModalOpen(true);
            } else {
              closeTunnelPingModal();
            }
          }}
          size="3xl"
          scrollBehavior="inside"
          backdrop="blur"
          placement="center"
          isDismissable
          isKeyboardDismissDisabled={false}
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">隧道一键 PING</h2>
                  <p className="text-small font-normal text-default-500">
                    {tunnelPingSummary?.tunnelName || '正在准备检测'}
                  </p>
                </ModalHeader>
                <ModalBody>
                  <Alert color="primary" variant="flat" title="检测范围" description={TCP_DIAGNOSIS_NOTICE} />
                  {tunnelPingSummary && (
                    <div className="grid grid-cols-3 gap-2">
                      <Card className="border border-divider shadow-none">
                        <CardBody className="items-center gap-0 py-3">
                          <span className="text-xl font-semibold">{tunnelPingSummary.totalForwards}</span>
                          <span className="text-tiny text-default-500">全部转发</span>
                        </CardBody>
                      </Card>
                      <Card className="border border-success/30 shadow-none">
                        <CardBody className="items-center gap-0 py-3">
                          <span className="text-xl font-semibold text-success">{tunnelPingSummary.successfulForwards}</span>
                          <span className="text-tiny text-default-500">TCP 连通</span>
                        </CardBody>
                      </Card>
                      <Card className="border border-danger/30 shadow-none">
                        <CardBody className="items-center gap-0 py-3">
                          <span className="text-xl font-semibold text-danger">{tunnelPingSummary.failedForwards}</span>
                          <span className="text-tiny text-default-500">TCP 未连通</span>
                        </CardBody>
                      </Card>
                    </div>
                  )}

                  {tunnelPingSummary && (
                    <div className="flex items-center gap-2 rounded-lg bg-primary-50 px-3 py-2 text-small text-primary dark:bg-primary-100/10">
                      {tunnelPingLoading && <Spinner size="sm" color="primary" />}
                      <span>{diagnosisProgressText(tunnelPingSummary, tunnelPingLoading)}{tunnelPingLoading && '；关闭将请求取消剩余检测'}</span>
                    </div>
                  )}
                  {tunnelDiagnosis.error && <Alert color="warning" variant="flat" title="进度提示" description={tunnelDiagnosis.error} />}
                  {tunnelPingSummary?.message && <Alert color="warning" variant="flat" title="任务说明" description={tunnelPingSummary.message} />}

                  {tunnelPingSummary?.forwards.length ? (
                    <div className="space-y-3 pb-1">
                      {tunnelPingSummary.forwards.map((forward) => (
                        <Card
                          key={forward.forwardId}
                          className={`border shadow-none ${forward.success ? 'border-success/40' : 'border-danger/40'}`}
                        >
                          <CardHeader className="items-start justify-between gap-3 pb-2">
                            <div className="min-w-0">
                              <h3 className="truncate text-base font-semibold">{forward.forwardName}</h3>
                              <code className="block truncate text-tiny text-default-500" title={forward.remoteAddress}>
                                {forward.remoteAddress || '-'}
                              </code>
                            </div>
                            <Chip color={forward.success ? 'success' : 'danger'} variant="flat" size="sm">
                                  {forward.success ? 'TCP 连通' : forward.outcome && forward.outcome !== 'failed' ? '未检测' : 'TCP 未连通'}
                            </Chip>
                          </CardHeader>
                          <CardBody className="gap-2 pt-0">
                            {proxyProtocolDiagnosisNotice(forward.proxyProtocol ?? forwards.find(rule => rule.id === forward.forwardId)?.proxyProtocol) && (
                              <p className="text-small text-warning">{proxyProtocolDiagnosisNotice(forward.proxyProtocol ?? forwards.find(rule => rule.id === forward.forwardId)?.proxyProtocol)}</p>
                            )}
                            {forward.results.length ? forward.results.map((result, index) => (
                              <div key={`${forward.forwardId}-${index}`} className="flex items-center justify-between gap-3 rounded-medium bg-default-100 px-3 py-2 dark:bg-default-50/10">
                                <span className="min-w-0 truncate text-small">{result.description}</span>
                                <span className={`shrink-0 text-tiny font-medium ${result.success ? 'text-success' : 'text-danger'}`}>
                                  {result.success
                                    ? `${result.averageTime?.toFixed(0) ?? '-'} ms · ${result.packetLoss?.toFixed(0) ?? '-'}% 连接失败`
                                    : '连接失败'}
                                </span>
                              </div>
                            )) : (
                              <Alert color={forward.outcome && forward.outcome !== 'failed' ? 'warning' : 'danger'} variant="flat" title={forward.outcome && forward.outcome !== 'failed' ? '未完成原因' : '检测失败'} description={forward.message || '未返回有效检测数据'} />
                            )}
                            {!forward.success && forward.results.length > 0 && forward.message && (
                              <p className="text-tiny text-danger">{forward.message}</p>
                            )}
                          </CardBody>
                        </Card>
                      ))}
                    </div>
                  ) : !tunnelPingLoading ? (
                    <div className="py-12 text-center text-small text-default-500">暂无检测结果</div>
                  ) : null}
                </ModalBody>
                <ModalFooter>
                  {tunnelPingLoading && tunnelPingSummary?.taskId && (
                    <Button color="warning" variant="flat" isLoading={tunnelDiagnosis.cancelling} onPress={() => void tunnelDiagnosis.cancel()}>取消剩余检测</Button>
                  )}
                  <Button variant="light" onPress={onClose}>
                    {tunnelPingLoading ? '取消并关闭' : '关闭'}
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 单条转发诊断结果模态框 */}
        <Modal 
          isOpen={diagnosisModalOpen}
          onOpenChange={setDiagnosisModalOpen}
          
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">转发诊断结果</h2>
                  {currentDiagnosisForward && (
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-small text-default-500 truncate flex-1 min-w-0">{currentDiagnosisForward.name}</span>
                      <Chip 
                        color="primary"
                        variant="flat" 
                        size="sm"
                        className="flex-shrink-0"
                      >
                        转发服务
                      </Chip>
                    </div>
                  )}
                </ModalHeader>
                <ModalBody>
                  <Alert color="primary" variant="flat" title="检测范围" description={TCP_DIAGNOSIS_NOTICE} />
                  {proxyProtocolDiagnosisNotice(diagnosisResult?.proxyProtocol ?? currentDiagnosisForward?.proxyProtocol) && (
                    <p className="text-small text-warning">{proxyProtocolDiagnosisNotice(diagnosisResult?.proxyProtocol ?? currentDiagnosisForward?.proxyProtocol)}</p>
                  )}
                  {diagnosisLoading ? (
                    <div className="flex items-center justify-center py-16">
                      <div className="flex items-center gap-3">
                        <Spinner size="sm" />
                        <span className="text-default-600">正在诊断转发连接...</span>
                      </div>
                    </div>
                  ) : diagnosisResult ? (
                    <div className="space-y-4">
                      {diagnosisResult.results.map((result, index) => {
                        const quality = getQualityDisplay(result.averageTime, result.packetLoss);
                        
                        return (
                          <Card key={index} className={`shadow-sm border ${result.success ? 'border-success' : 'border-danger'}`}>
                            <CardHeader className="pb-2">
                              <div className="flex items-center justify-between w-full">
                                <div>
                                  <h3 className="text-lg font-semibold text-foreground">{result.description}</h3>
                                  <div className="flex items-center gap-2 mt-1">
                                    <span className="text-small text-default-500">节点: {result.nodeName}</span>
                                    <Chip 
                                      color={result.success ? 'success' : 'danger'} 
                                      variant="flat" 
                                      size="sm"
                                    >
                                      {result.success ? 'TCP 连通' : 'TCP 未连通'}
                                    </Chip>
                                  </div>
                                </div>
                              </div>
                            </CardHeader>
                            
                            <CardBody className="pt-0">
                              {result.success ? (
                                <div className="space-y-3">
                                  <div className="grid grid-cols-3 gap-4">
                                    <div className="text-center">
                                      <div className="text-2xl font-bold text-primary">{result.averageTime?.toFixed(0)}</div>
                                      <div className="text-small text-default-500">平均延迟(ms)</div>
                                    </div>
                                    <div className="text-center">
                                      <div className="text-2xl font-bold text-warning">{result.packetLoss?.toFixed(1)}</div>
                                      <div className="text-small text-default-500">连接失败率(%)</div>
                                    </div>
                                    <div className="text-center">
                                      {quality && (
                                        <>
                                          <Chip color={quality.color as any} variant="flat" size="lg">
                                            {quality.text}
                                          </Chip>
                                          <div className="text-small text-default-500 mt-1">连接质量</div>
                                        </>
                                      )}
                                    </div>
                                  </div>
                                  <div className="text-small text-default-500 flex items-center gap-1">
                                    <span className="flex-shrink-0">目标地址:</span>
                                    <code className="font-mono truncate min-w-0" title={`${result.targetIp}${result.targetPort ? ':' + result.targetPort : ''}`}>
                                      {result.targetIp}{result.targetPort ? ':' + result.targetPort : ''}
                                    </code>
                                  </div>
                                </div>
                              ) : (
                                <div className="space-y-2">
                                  <div className="text-small text-default-500 flex items-center gap-1">
                                    <span className="flex-shrink-0">目标地址:</span>
                                    <code className="font-mono truncate min-w-0" title={`${result.targetIp}${result.targetPort ? ':' + result.targetPort : ''}`}>
                                      {result.targetIp}{result.targetPort ? ':' + result.targetPort : ''}
                                    </code>
                                  </div>
                                  <Alert
                                    color="danger"
                                    variant="flat"
                                    title="错误详情"
                                    description={result.message}
                                  />
                                </div>
                              )}
                            </CardBody>
                          </Card>
                        );
                      })}
                    </div>
                  ) : (
                    <div className="text-center py-16">
                      <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center mx-auto mb-4">
                        <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9.75 9.75l4.5 4.5m0-4.5l-4.5 4.5M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                        </svg>
                      </div>
                      <h3 className="text-lg font-semibold text-foreground">暂无诊断数据</h3>
                    </div>
                  )}
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    关闭
                  </Button>
                  {currentDiagnosisForward && (
                    <Button 
                      color="primary" 
                      onPress={() => handleDiagnose(currentDiagnosisForward)}
                      isLoading={diagnosisLoading}
                    >
                      重新诊断
                    </Button>
                  )}
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>
      </div>
    
  );
}
