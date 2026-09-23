package com.admin.service.impl;

import com.admin.common.dto.VpsActionDto;
import com.admin.common.dto.VpsHostDto;
import com.admin.common.dto.VpsHostUpdateDto;
import com.admin.common.dto.VpsHostView;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.HttpContextUtils;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.VpsSshTargetPolicy;
import com.admin.entity.User;
import com.admin.entity.VpsHost;
import com.admin.mapper.UserMapper;
import com.admin.mapper.VpsHostMapper;
import com.admin.service.VpsHostService;
import com.admin.service.VpsSshService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Implements the ownership and assignment rules for VPS hosting. */
@Service
public class VpsHostServiceImpl extends ServiceImpl<VpsHostMapper, VpsHost> implements VpsHostService {

    private static final String ORIGIN_USER = "USER";
    private static final String ORIGIN_ADMIN = "ADMIN";
    private static final int ACTIVE_STATUS = 1;

    @Resource
    private UserMapper userMapper;

    @Resource
    private VpsSshService vpsSshService;

    @Resource
    private VpsSshTargetPolicy vpsSshTargetPolicy;

    @Value("${jwt-secret}")
    private String jwtSecret;

    private volatile AESCrypto credentialCrypto;

    @Override
    public R listHosts() {
        Actor actor = currentActor();
        return R.ok(toViews(listAccessibleHosts(actor.userId, actor.administrator), actor));
    }

    @Override
    public R createHost(VpsHostDto hostDto) {
        Actor actor = currentActor();
        boolean administrator = actor.administrator;

        String normalizedHost;
        try {
            normalizedHost = vpsSshTargetPolicy.normalizeForStorage(hostDto.getHost(), administrator);
        } catch (IllegalArgumentException exception) {
            return R.err(exception.getMessage());
        }
        if (administrator && hostDto.getAssignedUserId() != null && !isAssignableUser(hostDto.getAssignedUserId())) {
            return R.err("请选择有效的普通用户进行分配");
        }

        long now = System.currentTimeMillis();
        VpsHost host = new VpsHost();
        host.setName(hostDto.getName().trim());
        host.setHost(normalizedHost);
        host.setSshPort(hostDto.getSshPort());
        host.setSshUsername(hostDto.getSshUsername().trim());
        host.setSshPassword(encryptCredential(hostDto.getSshPassword()));
        host.setOrigin(administrator ? ORIGIN_ADMIN : ORIGIN_USER);
        host.setOwnerUserId(administrator ? null : actor.userId);
        host.setAssignedUserId(administrator ? hostDto.getAssignedUserId() : null);
        host.setRemark(trimToNull(hostDto.getRemark()));
        host.setHealthStatus("unknown");
        host.setCreatedTime(now);
        host.setUpdatedTime(now);
        host.setStatus(ACTIVE_STATUS);

        if (!save(host)) return R.err("VPS 托管创建失败");
        return R.ok(toView(host, actor, loadUserNames(Collections.singletonList(host))));
    }

    @Override
    public R updateHost(VpsHostUpdateDto hostDto) {
        Actor actor = currentActor();
        VpsHost host = getById(hostDto.getId());
        if (host == null || !Objects.equals(host.getStatus(), ACTIVE_STATUS)) return R.err("VPS 不存在");
        if (!canManage(host, actor)) return R.err(403, "你只能修改自己托管的 VPS；管理员托管并分配的 VPS 不可修改连接凭据");

        String normalizedHost;
        try {
            normalizedHost = vpsSshTargetPolicy.normalizeForStorage(hostDto.getHost(), ORIGIN_ADMIN.equals(host.getOrigin()));
        } catch (IllegalArgumentException exception) {
            return R.err(exception.getMessage());
        }
        if (actor.administrator && ORIGIN_ADMIN.equals(host.getOrigin())
                && hostDto.getAssignedUserId() != null && !isAssignableUser(hostDto.getAssignedUserId())) {
            return R.err("请选择有效的普通用户进行分配");
        }

        boolean endpointChanged = !Objects.equals(host.getHost(), normalizedHost)
                || !Objects.equals(host.getSshPort(), hostDto.getSshPort());
        host.setName(hostDto.getName().trim());
        host.setHost(normalizedHost);
        host.setSshPort(hostDto.getSshPort());
        host.setSshUsername(hostDto.getSshUsername().trim());
        host.setRemark(trimToNull(hostDto.getRemark()));
        if (!isBlank(hostDto.getSshPassword())) {
            host.setSshPassword(encryptCredential(hostDto.getSshPassword()));
        }
        if (actor.administrator && ORIGIN_ADMIN.equals(host.getOrigin())) host.setAssignedUserId(hostDto.getAssignedUserId());
        if (endpointChanged) {
            host.setSshFingerprint(null);
            host.setHealthStatus("unknown");
            host.setLastCheckMessage("SSH 地址或端口已变更，等待重新验证主机指纹");
            host.setLastCheckTime(null);
            host.setLastLatencyMs(null);
        }
        host.setUpdatedTime(System.currentTimeMillis());
        if (!updateById(host)) return R.err("VPS 托管更新失败");

        // MyBatis-Plus does not update null fields by default. Explicit sets
        // are required to make "unassign" and endpoint re-verification real.
        if (actor.administrator && ORIGIN_ADMIN.equals(host.getOrigin())) {
            update(new VpsHost(), new UpdateWrapper<VpsHost>().eq("id", host.getId())
                    .set("assigned_user_id", hostDto.getAssignedUserId()));
        }
        if (endpointChanged) {
            update(new VpsHost(), new UpdateWrapper<VpsHost>().eq("id", host.getId())
                    .set("ssh_fingerprint", null)
                    .set("last_check_time", null)
                    .set("last_latency_ms", null));
        }
        return R.ok(toView(host, actor, loadUserNames(Collections.singletonList(host))));
    }

    @Override
    public R deleteHost(VpsActionDto actionDto) {
        Actor actor = currentActor();
        VpsHost host = getById(actionDto.getId());
        if (host == null || !Objects.equals(host.getStatus(), ACTIVE_STATUS)) return R.err("VPS 不存在");
        if (!canManage(host, actor)) return R.err(403, "没有删除此 VPS 的权限");

        VpsHost update = new VpsHost();
        update.setId(host.getId());
        update.setStatus(0);
        update.setUpdatedTime(System.currentTimeMillis());
        return updateById(update) ? R.ok("VPS 托管已移除") : R.err("VPS 托管移除失败");
    }

    @Override
    public R checkHost(VpsActionDto actionDto) {
        Actor actor = currentActor();
        VpsHost host = findOperableHost(actor.userId, actor.administrator, actionDto.getId());
        if (host == null) return R.err(403, "没有检测此 VPS 的权限");
        checkHostInternal(host);
        VpsHost latest = getById(host.getId());
        return R.ok(toView(latest == null ? host : latest, actor, loadUserNames(Collections.singletonList(host))));
    }

    @Override
    public R resetFingerprint(VpsActionDto actionDto) {
        Actor actor = currentActor();
        VpsHost host = getById(actionDto.getId());
        if (host == null || !Objects.equals(host.getStatus(), ACTIVE_STATUS)) return R.err("VPS 不存在");
        if (!canManage(host, actor)) return R.err(403, "没有重新验证此 VPS SSH 指纹的权限");

        long now = System.currentTimeMillis();
        if (!update(new VpsHost(), new UpdateWrapper<VpsHost>().eq("id", host.getId())
                .set("ssh_fingerprint", null)
                .set("health_status", "unknown")
                .set("last_check_message", "SSH 主机指纹已清除，请重新执行检测确认新服务器身份")
                .set("last_check_time", null)
                .set("last_latency_ms", null)
                .set("updated_time", now))) return R.err("重置 SSH 主机指纹失败");
        host.setSshFingerprint(null);
        host.setHealthStatus("unknown");
        host.setLastCheckMessage("SSH 主机指纹已清除，请重新执行检测确认新服务器身份");
        host.setLastCheckTime(null);
        host.setLastLatencyMs(null);
        return R.ok(toView(host, actor, loadUserNames(Collections.singletonList(host))));
    }

    @Override
    public R listAssignableUsers() {
        Actor actor = currentActor();
        if (!actor.administrator) return R.err(403, "仅管理员可查看可分配用户");
        List<Map<String, Object>> users = userMapper.selectList(new QueryWrapper<User>()
                        .eq("status", ACTIVE_STATUS)
                        .ne("role_id", 0)
                        .orderByAsc("user"))
                .stream()
                .map(user -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", user.getId());
                    item.put("user", user.getUser());
                    return item;
                })
                .collect(Collectors.toList());
        return R.ok(users);
    }

    @Override
    public VpsHost findOperableHost(Long userId, boolean administrator, Long hostId) {
        if (hostId == null) return null;
        VpsHost host = getById(hostId);
        if (host == null || !Objects.equals(host.getStatus(), ACTIVE_STATUS)) return null;
        return canOperate(host, new Actor(userId, administrator)) ? host : null;
    }

    @Override
    public boolean canOperate(Long userId, boolean administrator, Long hostId) {
        return findOperableHost(userId, administrator, hostId) != null;
    }

    @Override
    public List<VpsHost> listAccessibleHosts(Long userId, boolean administrator) {
        QueryWrapper<VpsHost> query = new QueryWrapper<VpsHost>()
                .eq("status", ACTIVE_STATUS)
                .orderByDesc("created_time");
        if (!administrator) {
            query.and(wrapper -> wrapper.eq("owner_user_id", userId).or().eq("assigned_user_id", userId));
        }
        return list(query);
    }

    @Override
    public void recordSshSuccess(VpsHost host, String fingerprint, String message) {
        if (host == null || host.getId() == null) return;
        long now = System.currentTimeMillis();
        UpdateWrapper<VpsHost> update = new UpdateWrapper<VpsHost>()
                .eq("id", host.getId())
                .eq("status", ACTIVE_STATUS)
                .set("health_status", "online")
                .set("last_check_time", now)
                .set("last_check_message", limit(isBlank(message) ? "SSH 认证成功" : message, 500))
                .set("last_latency_ms", null)
                .set("updated_time", now);
        if (isBlank(host.getSshFingerprint()) && !isBlank(fingerprint) && !"unknown".equals(fingerprint)) {
            update.set("ssh_fingerprint", fingerprint);
            host.setSshFingerprint(fingerprint);
        }
        update(new VpsHost(), update);
        host.setHealthStatus("online");
        host.setLastCheckTime(now);
        host.setLastCheckMessage(isBlank(message) ? "SSH 认证成功" : message);
        host.setLastLatencyMs(null);
    }

    @Override
    public void recordSshFailure(VpsHost host, String message, boolean fingerprintChanged) {
        if (host == null || host.getId() == null) return;
        long now = System.currentTimeMillis();
        String status = fingerprintChanged ? "fingerprint_changed" : "offline";
        String safeMessage = limit(isBlank(message) ? "SSH 连接失败" : message, 500);
        update(new VpsHost(), new UpdateWrapper<VpsHost>()
                .eq("id", host.getId())
                .eq("status", ACTIVE_STATUS)
                .set("health_status", status)
                .set("last_check_time", now)
                .set("last_check_message", safeMessage)
                .set("last_latency_ms", null)
                .set("updated_time", now));
        host.setHealthStatus(status);
        host.setLastCheckTime(now);
        host.setLastCheckMessage(safeMessage);
        host.setLastLatencyMs(null);
    }

    @Override
    public String decryptSshPassword(VpsHost host) {
        if (host == null || isBlank(host.getSshPassword())) return null;
        try {
            return getCredentialCrypto().decryptString(host.getSshPassword());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void checkHostInternal(VpsHost host) {
        String password = decryptSshPassword(host);
        VpsSshService.HealthCheckResult result = vpsSshService.check(host, password);
        long now = System.currentTimeMillis();
        UpdateWrapper<VpsHost> updateWrapper = new UpdateWrapper<VpsHost>().eq("id", host.getId())
                .set("last_check_time", now)
                .set("last_check_message", limit(result.getMessage(), 500))
                .set("last_latency_ms", result.isOnline() ? result.getLatencyMs() : null)
                .set("health_status", result.isFingerprintChanged() ? "fingerprint_changed" : result.isOnline() ? "online" : "offline")
                .set("updated_time", now);
        if (isBlank(host.getSshFingerprint()) && result.isOnline() && !isBlank(result.getFingerprint())) {
            updateWrapper.set("ssh_fingerprint", result.getFingerprint());
            host.setSshFingerprint(result.getFingerprint());
        }
        update(new VpsHost(), updateWrapper);
    }

    private Actor currentActor() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        return new Actor(JwtUtil.getUserIdFromToken(token), Objects.equals(JwtUtil.getRoleIdFromToken(token), 0));
    }

    private boolean canOperate(VpsHost host, Actor actor) {
        return actor.administrator || Objects.equals(host.getOwnerUserId(), actor.userId)
                || Objects.equals(host.getAssignedUserId(), actor.userId);
    }

    private boolean canManage(VpsHost host, Actor actor) {
        return actor.administrator || (ORIGIN_USER.equals(host.getOrigin()) && Objects.equals(host.getOwnerUserId(), actor.userId));
    }

    private boolean isAssignableUser(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && Objects.equals(user.getStatus(), ACTIVE_STATUS) && !Objects.equals(user.getRoleId(), 0);
    }

    private List<VpsHostView> toViews(List<VpsHost> hosts, Actor actor) {
        Map<Long, String> userNames = loadUserNames(hosts);
        return hosts.stream().map(host -> toView(host, actor, userNames)).collect(Collectors.toList());
    }

    private VpsHostView toView(VpsHost host, Actor actor, Map<Long, String> userNames) {
        VpsHostView view = new VpsHostView();
        BeanUtils.copyProperties(host, view, "sshPassword", "status");
        view.setOwnerUserName(userNames.get(host.getOwnerUserId()));
        view.setAssignedUserName(userNames.get(host.getAssignedUserId()));
        view.setCanOperate(canOperate(host, actor));
        view.setCanManage(canManage(host, actor));
        view.setCanAssign(actor.administrator && ORIGIN_ADMIN.equals(host.getOrigin()));
        return view;
    }

    private Map<Long, String> loadUserNames(Collection<VpsHost> hosts) {
        List<Long> userIds = hosts.stream()
                .flatMap(host -> {
                    List<Long> ids = new ArrayList<>();
                    if (host.getOwnerUserId() != null) ids.add(host.getOwnerUserId());
                    if (host.getAssignedUserId() != null) ids.add(host.getAssignedUserId());
                    return ids.stream();
                })
                .distinct()
                .collect(Collectors.toList());
        if (userIds.isEmpty()) return Collections.emptyMap();
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUser, (left, right) -> left));
    }

    private String encryptCredential(String value) {
        return getCredentialCrypto().encrypt(value);
    }

    private AESCrypto getCredentialCrypto() {
        AESCrypto crypto = credentialCrypto;
        if (crypto == null) {
            synchronized (this) {
                crypto = credentialCrypto;
                if (crypto == null) {
                    crypto = new AESCrypto(jwtSecret + ":vps-host-credential:v1");
                    credentialCrypto = crypto;
                }
            }
        }
        return crypto;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String limit(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static final class Actor {
        private final Long userId;
        private final boolean administrator;

        private Actor(Long userId, boolean administrator) {
            this.userId = userId;
            this.administrator = administrator;
        }
    }
}
