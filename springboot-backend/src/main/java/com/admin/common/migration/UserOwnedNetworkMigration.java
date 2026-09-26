package com.admin.common.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/** Preserve legacy administrator inventory while enabling isolated user resources. */
@Component
public class UserOwnedNetworkMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        addOwnerColumn("node");
        addOwnerColumn("tunnel");
    }

    private void addOwnerColumn(String table) {
        Integer columns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = ? AND COLUMN_NAME = 'owner_user_id'",
                Integer.class, table);
        if (columns == null || columns == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN owner_user_id BIGINT NULL");
        }
        String index = "idx_" + table + "_owner_user_id";
        Integer indexes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, index);
        if (indexes == null || indexes == 0) {
            jdbcTemplate.execute("CREATE INDEX " + index + " ON `" + table + "` (owner_user_id)");
        }
    }
}
