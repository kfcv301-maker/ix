package com.admin.common.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * Small, idempotent schema additions for secure, high-frequency flow reports
 * and durable pause retries.  Existing installations are upgraded at startup.
 */
@Component
public class FlowSafetyMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS flow_report_cursor ("
                + "node_id BIGINT NOT NULL,"
                + "service_name VARCHAR(160) NOT NULL,"
                + "session_id VARCHAR(96) NOT NULL,"
                + "session_started_at BIGINT NOT NULL,"
                + "last_sequence BIGINT NOT NULL DEFAULT 0,"
                + "created_time BIGINT NOT NULL,"
                + "updated_time BIGINT NOT NULL,"
                + "PRIMARY KEY (node_id, service_name),"
                + "KEY idx_flow_report_cursor_updated (updated_time)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS forward_pause_task ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "forward_id BIGINT NOT NULL,"
                + "task_status VARCHAR(16) NOT NULL,"
                + "attempts INT NOT NULL DEFAULT 0,"
                + "last_error VARCHAR(500) NULL,"
                + "created_time BIGINT NOT NULL,"
                + "updated_time BIGINT NOT NULL,"
                + "status INT NOT NULL DEFAULT 1,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_forward_pause_task_forward (forward_id),"
                + "KEY idx_forward_pause_task_state (task_status, updated_time)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        ensureIndex("node", "idx_node_secret", "CREATE INDEX idx_node_secret ON `node` (`secret`)");
        ensureIndex("forward", "idx_forward_user_tunnel", "CREATE INDEX idx_forward_user_tunnel ON `forward` (`user_id`, `tunnel_id`)");
        ensureIndex("forward", "idx_forward_tunnel", "CREATE INDEX idx_forward_tunnel ON `forward` (`tunnel_id`)");
        ensureIndex("user_tunnel", "idx_user_tunnel_user_tunnel", "CREATE INDEX idx_user_tunnel_user_tunnel ON `user_tunnel` (`user_id`, `tunnel_id`)");
        ensureIndex("statistics_flow", "idx_statistics_flow_user_id", "CREATE INDEX idx_statistics_flow_user_id ON `statistics_flow` (`user_id`, `id`)");
        ensureIndex("statistics_flow", "idx_statistics_flow_created", "CREATE INDEX idx_statistics_flow_created ON `statistics_flow` (`created_time`)");
    }

    private void ensureIndex(String table, String index, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count != null && count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }
}
