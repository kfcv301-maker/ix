package com.admin.common.migration;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * Adds an optional, non-destructive link from a forwarding rule to the hosted
 * VPS that provides its target service. It deliberately has no foreign key:
 * removing a VPS is a soft delete and existing forwards must retain their
 * operational history instead of becoming invalid database rows.
 */
@Component
@DependsOn("vpsHostingMigration")
public class ForwardVpsHostMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        if (!columnExists("forward", "vps_host_id")) {
            jdbcTemplate.execute("ALTER TABLE `forward` ADD COLUMN `vps_host_id` BIGINT NULL AFTER `remote_addr`");
        }
        if (!indexExists("forward", "idx_forward_vps_host")) {
            jdbcTemplate.execute("CREATE INDEX idx_forward_vps_host ON `forward` (`vps_host_id`)");
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, tableName, indexName);
        return count != null && count > 0;
    }
}
