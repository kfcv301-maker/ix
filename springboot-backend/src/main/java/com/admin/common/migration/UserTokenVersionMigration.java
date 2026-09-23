package com.admin.common.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * Adds durable token-version state for immediate JWT invalidation after a
 * password change. Existing tokens deliberately have no version claim and are
 * rejected once this protection is deployed.
 */
@Component
public class UserTokenVersionMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'user' AND column_name = 'token_version'",
                Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE `user` ADD COLUMN token_version INT NOT NULL DEFAULT 0 AFTER pwd");
        }
    }
}
