package com.admin.common.migration;

import lombok.extern.slf4j.Slf4j;
import com.admin.entity.UserTunnel;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/** Repairs historic duplicate grants, then enforces one entitlement per user/tunnel. */
@Slf4j
@Component
@DependsOn("tunnelEntryDomainMigration")
public class UserTunnelUniquenessMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private PlatformTransactionManager transactionManager;

    @PostConstruct
    public void migrate() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS user_tunnel_alias ("
                + "alias_id INT NOT NULL PRIMARY KEY, canonical_id INT NOT NULL, "
                + "user_id INT NOT NULL, tunnel_id INT NOT NULL, KEY idx_alias_canonical (canonical_id)) ENGINE=InnoDB");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS user_tunnel_duplicate_archive LIKE user_tunnel");
        Integer index = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'user_tunnel' "
                        + "AND index_name = 'uk_user_tunnel_user_tunnel'", Integer.class);
        if (index != null && index > 0) return;

        List<UserTunnel> groups = jdbcTemplate.query("SELECT user_id, tunnel_id FROM user_tunnel "
                + "GROUP BY user_id, tunnel_id HAVING COUNT(*) > 1", new BeanPropertyRowMapper<>(UserTunnel.class));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        for (UserTunnel group : groups) {
            transaction.executeWithoutResult(status -> mergeGroup(group));
        }
        jdbcTemplate.execute("CREATE UNIQUE INDEX uk_user_tunnel_user_tunnel ON user_tunnel (user_id, tunnel_id)");
    }

    private void mergeGroup(UserTunnel group) {
        List<UserTunnel> rows = jdbcTemplate.query("SELECT * FROM user_tunnel WHERE user_id = ? AND tunnel_id = ? "
                + "ORDER BY id FOR UPDATE", new BeanPropertyRowMapper<>(UserTunnel.class), group.getUserId(), group.getTunnelId());
        if (rows.size() < 2) return;
        // The latest grant supplies settings; archive all conflicting originals.
        UserTunnel canonical = rows.get(rows.size() - 1);
        long inbound = 0, outbound = 0;
        for (UserTunnel row : rows) {
            inbound = Math.addExact(inbound, row.getInFlow() == null ? 0 : row.getInFlow());
            outbound = Math.addExact(outbound, row.getOutFlow() == null ? 0 : row.getOutFlow());
        }
        jdbcTemplate.update("INSERT IGNORE INTO user_tunnel_duplicate_archive SELECT * FROM user_tunnel "
                + "WHERE user_id = ? AND tunnel_id = ?", group.getUserId(), group.getTunnelId());
        jdbcTemplate.update("INSERT INTO user_tunnel_alias (alias_id, canonical_id, user_id, tunnel_id) "
                + "SELECT id, ?, user_id, tunnel_id FROM user_tunnel WHERE user_id = ? AND tunnel_id = ? AND id <> ? "
                + "ON DUPLICATE KEY UPDATE canonical_id = VALUES(canonical_id)",
                canonical.getId(), group.getUserId(), group.getTunnelId(), canonical.getId());
        jdbcTemplate.update("UPDATE user_tunnel SET in_flow = ?, out_flow = ? WHERE id = ?",
                inbound, outbound, canonical.getId());
        jdbcTemplate.update("DELETE FROM user_tunnel WHERE user_id = ? AND tunnel_id = ? AND id <> ?",
                group.getUserId(), group.getTunnelId(), canonical.getId());
        log.warn("Consolidated {} grants for user {}/tunnel {} into {}, preserving original rows and Agent IDs",
                rows.size(), group.getUserId(), group.getTunnelId(), canonical.getId());
    }
}
