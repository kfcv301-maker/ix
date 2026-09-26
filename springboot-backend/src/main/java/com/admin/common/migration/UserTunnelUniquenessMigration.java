package com.admin.common.migration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/** Repairs historic duplicate grants, then enforces one entitlement per user/tunnel. */
@Slf4j
@Component
public class UserTunnelUniquenessMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        Integer index = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'user_tunnel' "
                        + "AND index_name = 'uk_user_tunnel_user_tunnel'", Integer.class);
        if (index != null && index > 0) return;

        Integer duplicates = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (SELECT user_id, tunnel_id "
                        + "FROM user_tunnel GROUP BY user_id, tunnel_id HAVING COUNT(*) > 1) duplicate_grants",
                Integer.class);
        if (duplicates != null && duplicates > 0) {
            // Duplicate rows represent the same entitlement. No table refers
            // to user_tunnel.id, so retaining the earliest row preserves the
            // effective permission while making reads deterministic.
            int removed = jdbcTemplate.update("DELETE duplicate_row FROM user_tunnel duplicate_row "
                    + "INNER JOIN user_tunnel retained_row ON duplicate_row.user_id = retained_row.user_id "
                    + "AND duplicate_row.tunnel_id = retained_row.tunnel_id AND duplicate_row.id > retained_row.id");
            log.warn("Removed {} historic duplicate user_tunnel entitlement rows before adding a unique constraint", removed);
        }
        jdbcTemplate.execute("CREATE UNIQUE INDEX uk_user_tunnel_user_tunnel ON user_tunnel (user_id, tunnel_id)");
    }
}
