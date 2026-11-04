package com.admin.common.migration;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NodeIpHistoryMigration {
    private final JdbcTemplate jdbc;
    public NodeIpHistoryMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void migrate() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS node_ip_state (node_id BIGINT NOT NULL PRIMARY KEY, "
                + "payload TEXT NOT NULL, checked_at BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS node_ip_event (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "node_id BIGINT NOT NULL, kind VARCHAR(24) NOT NULL, target VARCHAR(253) NOT NULL, "
                + "source VARCHAR(16) NOT NULL, previous_addresses TEXT NOT NULL, addresses TEXT NOT NULL, "
                + "observed_at BIGINT NOT NULL, confirmed_at BIGINT NOT NULL, "
                + "KEY idx_node_ip_event_page (node_id,id), "
                + "KEY idx_node_ip_event_count (node_id,kind,confirmed_at), "
                + "KEY idx_node_ip_event_retention (confirmed_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        Integer columns=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='node_ip_event' AND COLUMN_NAME='context_json'",Integer.class);
        if (columns!=null && columns==0) jdbc.execute("ALTER TABLE node_ip_event ADD COLUMN context_json TEXT NULL");
    }
}
