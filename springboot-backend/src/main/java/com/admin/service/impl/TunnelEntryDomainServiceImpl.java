package com.admin.service.impl;

import com.admin.common.dto.TunnelEntryDomainDto;
import com.admin.common.dto.TunnelEntryDomainView;
import com.admin.common.lang.R;
import com.admin.entity.Tunnel;
import com.admin.entity.TunnelEntryDomain;
import com.admin.entity.UserTunnel;
import com.admin.mapper.TunnelEntryDomainMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.TunnelEntryDomainService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Maintains the administrative domain pool for a tunnel. This service never
 * creates DNS records or changes GOST; it only stores validated display
 * addresses and safely resolves the address assigned to a panel user.
 */
@Service
public class TunnelEntryDomainServiceImpl extends ServiceImpl<TunnelEntryDomainMapper, TunnelEntryDomain>
        implements TunnelEntryDomainService {

    @Override
    public TunnelEntryDomain getDomainById(Long id) {
        return id == null ? null : getById(id);
    }

    private static final int ACTIVE_STATUS = 1;
    private static final int DEFAULT_DOMAIN = 1;
    private static final int OPTIONAL_DOMAIN = 0;
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$",
            Pattern.CASE_INSENSITIVE);

    @Resource
    private TunnelMapper tunnelMapper;

    @Resource
    private UserTunnelMapper userTunnelMapper;

    @Override
    public R getTunnelEntryDomains(Long tunnelId) {
        if (tunnelMapper.selectById(tunnelId) == null) {
            return R.err("隧道不存在");
        }
        return R.ok(toViews(findActiveDomains(tunnelId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R createTunnelEntryDomain(TunnelEntryDomainDto dto) {
        if (tunnelMapper.selectById(dto.getTunnelId()) == null) {
            return R.err("隧道不存在");
        }

        String domain = normalizeDomain(dto.getDomain());
        if (domain == null) {
            return R.err("请输入有效的解析域名，例如 game.example.com");
        }
        if (this.count(new QueryWrapper<TunnelEntryDomain>()
                .eq("tunnel_id", dto.getTunnelId())
                .eq("domain", domain)) > 0) {
            return R.err("该解析域名已添加到此隧道");
        }

        boolean shouldBecomeDefault = Boolean.TRUE.equals(dto.getDefaultDomain())
                || findDefaultDomain(dto.getTunnelId()) == null;
        if (shouldBecomeDefault) {
            clearDefaultDomain(dto.getTunnelId());
        }

        long now = System.currentTimeMillis();
        TunnelEntryDomain entryDomain = new TunnelEntryDomain();
        entryDomain.setTunnelId(dto.getTunnelId());
        entryDomain.setDomain(domain);
        entryDomain.setIsDefault(shouldBecomeDefault ? DEFAULT_DOMAIN : OPTIONAL_DOMAIN);
        entryDomain.setCreatedTime(now);
        entryDomain.setUpdatedTime(now);
        entryDomain.setStatus(ACTIVE_STATUS);
        if (!this.save(entryDomain)) {
            throw new IllegalStateException("保存解析域名失败");
        }
        return R.ok(toView(entryDomain));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R setDefaultTunnelEntryDomain(Long id) {
        TunnelEntryDomain entryDomain = getActiveDomain(id);
        if (entryDomain == null) {
            return R.err("解析域名不存在或已删除");
        }
        clearDefaultDomain(entryDomain.getTunnelId());
        entryDomain.setIsDefault(DEFAULT_DOMAIN);
        entryDomain.setUpdatedTime(System.currentTimeMillis());
        if (!this.updateById(entryDomain)) {
            throw new IllegalStateException("设置默认解析域名失败");
        }
        return R.ok("默认解析域名已更新");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R deleteTunnelEntryDomain(Long id) {
        TunnelEntryDomain entryDomain = getActiveDomain(id);
        if (entryDomain == null) {
            return R.err("解析域名不存在或已删除");
        }

        int customAssignments = userTunnelMapper.selectCount(new QueryWrapper<UserTunnel>()
                .eq("entry_address_mode", ENTRY_ADDRESS_MODE_CUSTOM)
                .eq("entry_domain_id", id));
        int defaultAssignments = isDefault(entryDomain)
                ? userTunnelMapper.selectCount(new QueryWrapper<UserTunnel>()
                    .eq("tunnel_id", entryDomain.getTunnelId())
                    .eq("entry_address_mode", ENTRY_ADDRESS_MODE_DEFAULT))
                : 0;
        int assignmentCount = customAssignments + defaultAssignments;
        if (assignmentCount > 0) {
            return R.err("该解析域名正被 " + assignmentCount
                    + " 个用户使用，请先将这些用户改为其他解析域名、默认解析域名或原始入口");
        }

        return this.removeById(id) ? R.ok("解析域名已删除") : R.err("删除解析域名失败");
    }

    @Override
    public R validateInitialDomains(List<String> domains, String defaultDomain) {
        try {
            prepareInitialDomains(domains, defaultDomain);
            return R.ok();
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
    }

    @Override
    public void createInitialDomains(Long tunnelId, List<String> domains, String defaultDomain) {
        InitialDomainConfig config = prepareInitialDomains(domains, defaultDomain);
        if (config.domains.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (String domain : config.domains) {
            TunnelEntryDomain entryDomain = new TunnelEntryDomain();
            entryDomain.setTunnelId(tunnelId);
            entryDomain.setDomain(domain);
            entryDomain.setIsDefault(domain.equals(config.defaultDomain) ? DEFAULT_DOMAIN : OPTIONAL_DOMAIN);
            entryDomain.setCreatedTime(now);
            entryDomain.setUpdatedTime(now);
            entryDomain.setStatus(ACTIVE_STATUS);
            if (this.baseMapper.insert(entryDomain) != 1) {
                throw new IllegalStateException("保存解析域名失败");
            }
        }
    }

    @Override
    public R validateUserEntryAssignment(Integer tunnelId, String mode, Long domainId) {
        if (tunnelId == null || tunnelMapper.selectById(tunnelId) == null) {
            return R.err("隧道不存在");
        }
        String normalizedMode = normalizeMode(mode);
        if (ENTRY_ADDRESS_MODE_NONE.equals(normalizedMode)) {
            return R.ok();
        }
        if (ENTRY_ADDRESS_MODE_DEFAULT.equals(normalizedMode)) {
            return findDefaultDomain(tunnelId.longValue()) == null
                    ? R.err("该隧道尚未设置默认解析域名，请改选原始入口或指定解析域名")
                    : R.ok();
        }
        if (ENTRY_ADDRESS_MODE_CUSTOM.equals(normalizedMode)) {
            if (domainId == null) {
                return R.err("请选择要分配的解析域名");
            }
            TunnelEntryDomain entryDomain = getActiveDomain(domainId);
            if (entryDomain == null || !Long.valueOf(tunnelId).equals(entryDomain.getTunnelId())) {
                return R.err("所选解析域名不属于该隧道或已不可用");
            }
            return R.ok();
        }
        return R.err("入口地址分配方式无效");
    }

    @Override
    public String resolveAssignedDomain(UserTunnel userTunnel) {
        if (userTunnel == null || userTunnel.getTunnelId() == null) {
            return null;
        }
        String mode = normalizeMode(userTunnel.getEntryAddressMode());
        if (ENTRY_ADDRESS_MODE_DEFAULT.equals(mode)) {
            TunnelEntryDomain entryDomain = findDefaultDomain(userTunnel.getTunnelId().longValue());
            return entryDomain == null ? null : entryDomain.getDomain();
        }
        if (ENTRY_ADDRESS_MODE_CUSTOM.equals(mode) && userTunnel.getEntryDomainId() != null) {
            TunnelEntryDomain entryDomain = getActiveDomain(userTunnel.getEntryDomainId());
            if (entryDomain != null && Long.valueOf(userTunnel.getTunnelId()).equals(entryDomain.getTunnelId())) {
                return entryDomain.getDomain();
            }
        }
        return null;
    }

    private List<TunnelEntryDomain> findActiveDomains(Long tunnelId) {
        return this.list(new QueryWrapper<TunnelEntryDomain>()
                .eq("tunnel_id", tunnelId)
                .eq("status", ACTIVE_STATUS)
                .orderByDesc("is_default")
                .orderByAsc("domain"));
    }

    private TunnelEntryDomain findDefaultDomain(Long tunnelId) {
        List<TunnelEntryDomain> domains = this.list(new QueryWrapper<TunnelEntryDomain>()
                .eq("tunnel_id", tunnelId)
                .eq("status", ACTIVE_STATUS)
                .eq("is_default", DEFAULT_DOMAIN)
                .orderByAsc("id")
                .last("LIMIT 1"));
        return domains.isEmpty() ? null : domains.get(0);
    }

    private TunnelEntryDomain getActiveDomain(Long id) {
        TunnelEntryDomain entryDomain = this.getById(id);
        return entryDomain != null && entryDomain.getStatus() != null && entryDomain.getStatus() == ACTIVE_STATUS
                ? entryDomain : null;
    }

    private void clearDefaultDomain(Long tunnelId) {
        this.baseMapper.update(null, new UpdateWrapper<TunnelEntryDomain>()
                .eq("tunnel_id", tunnelId)
                .eq("status", ACTIVE_STATUS)
                .set("is_default", OPTIONAL_DOMAIN)
                .set("updated_time", System.currentTimeMillis()));
    }

    private boolean isDefault(TunnelEntryDomain entryDomain) {
        return entryDomain.getIsDefault() != null && entryDomain.getIsDefault() == DEFAULT_DOMAIN;
    }

    private List<TunnelEntryDomainView> toViews(List<TunnelEntryDomain> domains) {
        return domains.stream().map(this::toView).collect(Collectors.toList());
    }

    private TunnelEntryDomainView toView(TunnelEntryDomain entryDomain) {
        TunnelEntryDomainView view = new TunnelEntryDomainView();
        view.setId(entryDomain.getId());
        view.setTunnelId(entryDomain.getTunnelId());
        view.setDomain(entryDomain.getDomain());
        view.setDefaultDomain(isDefault(entryDomain));
        return view;
    }

    private InitialDomainConfig prepareInitialDomains(List<String> domains, String defaultDomain) {
        if (domains == null || domains.isEmpty()) {
            if (StringUtils.isNotBlank(defaultDomain)) {
                throw new IllegalArgumentException("请先添加默认解析域名");
            }
            return new InitialDomainConfig(new ArrayList<>(), null);
        }

        Set<String> normalizedDomains = new LinkedHashSet<>();
        for (String rawDomain : domains) {
            String domain = normalizeDomain(rawDomain);
            if (domain == null) {
                throw new IllegalArgumentException("请输入有效的解析域名，例如 game.example.com");
            }
            normalizedDomains.add(domain);
        }

        String normalizedDefault = StringUtils.isBlank(defaultDomain) ? null : normalizeDomain(defaultDomain);
        if (StringUtils.isNotBlank(defaultDomain) && normalizedDefault == null) {
            throw new IllegalArgumentException("默认解析域名格式无效");
        }
        if (normalizedDefault != null && !normalizedDomains.contains(normalizedDefault)) {
            throw new IllegalArgumentException("默认解析域名必须在已添加的解析域名中");
        }
        List<String> domainList = new ArrayList<>(normalizedDomains);
        return new InitialDomainConfig(domainList,
                normalizedDefault == null ? domainList.get(0) : normalizedDefault);
    }

    private String normalizeDomain(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return DOMAIN_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    private String normalizeMode(String value) {
        if (StringUtils.isBlank(value)) {
            return ENTRY_ADDRESS_MODE_NONE;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static class InitialDomainConfig {
        private final List<String> domains;
        private final String defaultDomain;

        private InitialDomainConfig(List<String> domains, String defaultDomain) {
            this.domains = domains;
            this.defaultDomain = defaultDomain;
        }
    }
}
