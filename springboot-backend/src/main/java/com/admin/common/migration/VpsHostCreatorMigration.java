package com.admin.common.migration;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/** Records which account entered each hosted VPS without changing ownership. */
@Component
@DependsOn("vpsHostingMigration")
public class VpsHostCreatorMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        if (!columnExists("vps_host", "created_by_user_id")) {
            jdbcTemplate.execute("ALTER TABLE vps_host ADD COLUMN created_by_user_id BIGINT NULL AFTER origin");
        }
        if (!indexExists("vps_host", "idx_vps_host_creator")) {
            jdbcTemplate.execute("CREATE INDEX idx_vps_host_creator ON vps_host (created_by_user_id)");
        }
        // USER-origin records have an unambiguous historic creator. Old ADMIN
        // inventory remains visibly marked as a historical administrator entry
        // instead of inventing an account that did not create it.
        jdbcTemplate.update("UPDATE vps_host SET created_by_user_id = owner_user_id "
                + "WHERE created_by_user_id IS NULL AND origin = 'USER' AND owner_user_id IS NOT NULL");
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
