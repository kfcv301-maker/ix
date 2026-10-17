package com.admin.common.migration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Existing rules retain their byte-for-byte forwarding behavior after upgrade. */
@Component
public class ForwardProxyProtocolMigration {
    @Resource private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'forward' AND COLUMN_NAME = 'proxy_protocol'",
                Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `forward` ADD COLUMN `proxy_protocol` INT NOT NULL DEFAULT 0");
        }
    }
}
