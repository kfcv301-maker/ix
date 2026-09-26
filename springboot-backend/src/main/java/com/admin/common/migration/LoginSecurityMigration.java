package com.admin.common.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/** Enables the existing CAPTCHA on upgraded installations unless an operator explicitly changes it. */
@Component
public class LoginSecurityMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        jdbcTemplate.update("INSERT INTO vite_config (name, value, time) VALUES ('captcha_enabled', 'true', ?) "
                        + "ON DUPLICATE KEY UPDATE name = name", System.currentTimeMillis());
    }
}
