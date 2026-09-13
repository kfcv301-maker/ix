package com.admin.common.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * Adds multi-ingress support without changing existing tunnel rows. It is
 * idempotent and also backfills every legacy tunnel's original ingress node.
 */
@Component
public class TunnelEntryNodeMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS tunnel_entry_node ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "tunnel_id INT NOT NULL,"
                + "node_id INT NOT NULL,"
                + "created_time BIGINT NOT NULL,"
                + "updated_time BIGINT NULL,"
                + "status INT NULL,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_tunnel_entry_node (tunnel_id, node_id),"
                + "KEY idx_tunnel_entry_node_node (node_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        jdbcTemplate.update("INSERT IGNORE INTO tunnel_entry_node "
                + "(tunnel_id, node_id, created_time, updated_time, status) "
                + "SELECT id, in_node_id, created_time, updated_time, status FROM tunnel");
    }
}
