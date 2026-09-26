package com.admin.common.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * Keeps existing panel installations upgrade-safe: VPS hosting tables are
 * created on startup and never require users to import a separate SQL file.
 */
@Component
public class VpsHostingMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS vps_host ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "name VARCHAR(100) NOT NULL,"
                + "host VARCHAR(255) NOT NULL,"
                + "ssh_port INT NOT NULL DEFAULT 22,"
                + "ssh_username VARCHAR(100) NOT NULL,"
                + "ssh_password LONGTEXT NOT NULL,"
                + "origin VARCHAR(16) NOT NULL,"
                + "created_by_user_id BIGINT NULL,"
                + "owner_user_id BIGINT NULL,"
                + "assigned_user_id BIGINT NULL,"
                + "remark VARCHAR(1000) NULL,"
                + "ssh_fingerprint VARCHAR(255) NULL,"
                + "ssh_fingerprint_verified TINYINT NOT NULL DEFAULT 0,"
                + "health_status VARCHAR(32) NOT NULL DEFAULT 'unknown',"
                + "last_check_time BIGINT NULL,"
                + "last_check_message VARCHAR(500) NULL,"
                + "last_latency_ms BIGINT NULL,"
                + "created_time BIGINT NOT NULL,"
                + "updated_time BIGINT NULL,"
                + "status INT NOT NULL DEFAULT 1,"
                + "PRIMARY KEY (id),"
                + "KEY idx_vps_host_creator (created_by_user_id),"
                + "KEY idx_vps_host_owner (owner_user_id),"
                + "KEY idx_vps_host_assigned (assigned_user_id),"
                + "KEY idx_vps_host_status (status)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS vps_deployment_task ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "vps_id BIGINT NOT NULL,"
                + "requested_by_user_id BIGINT NOT NULL,"
                + "task_type VARCHAR(64) NOT NULL,"
                + "task_status VARCHAR(32) NOT NULL,"
                + "active_lock VARCHAR(64) NULL,"
                + "output_log MEDIUMTEXT NULL,"
                + "started_time BIGINT NULL,"
                + "finished_time BIGINT NULL,"
                + "created_time BIGINT NOT NULL,"
                + "updated_time BIGINT NULL,"
                + "status INT NOT NULL DEFAULT 1,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_vps_task_active_lock (active_lock),"
                + "KEY idx_vps_task_vps (vps_id),"
                + "KEY idx_vps_task_status (task_status)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
