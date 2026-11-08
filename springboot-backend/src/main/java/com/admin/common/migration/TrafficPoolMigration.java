package com.admin.common.migration;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TrafficPoolMigration {
    private final JdbcTemplate jdbc;
    public TrafficPoolMigration(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct public void migrate() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS node_group (id CHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, revision BIGINT NOT NULL DEFAULT 1) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS node_group_member (node_id BIGINT PRIMARY KEY, group_id CHAR(36) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS vps_group_member (host_id BIGINT PRIMARY KEY, group_id CHAR(36) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_pool (id CHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, dns_key CHAR(64) UNIQUE, payload LONGTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_pool_member (node_id BIGINT PRIMARY KEY, pool_id CHAR(36) NOT NULL, payload LONGTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_pool_tunnel (tunnel_id BIGINT PRIMARY KEY, pool_id CHAR(36) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_pool_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, pool_id CHAR(36) NOT NULL, happened_at BIGINT NOT NULL, kind VARCHAR(40) NOT NULL, detail VARCHAR(500) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
