package com.admin.common.migration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Nullable keys leave historical rules and old clients unchanged. */
@Component
public class ForwardCreateRequestMigration {
    @Resource private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        addColumn("create_request_id", "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL");
        addColumn("create_request_hash", "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL");
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'forward' AND INDEX_NAME = 'uk_forward_create_request'", Integer.class);
        if (count == null || count == 0) jdbcTemplate.execute(
                "ALTER TABLE `forward` ADD UNIQUE KEY `uk_forward_create_request` (`user_id`, `create_request_id`)");
    }

    private void addColumn(String name, String type) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'forward' AND COLUMN_NAME = ?", Integer.class, name);
        if (count == null || count == 0) jdbcTemplate.execute("ALTER TABLE `forward` ADD COLUMN `" + name + "` " + type);
    }
}
