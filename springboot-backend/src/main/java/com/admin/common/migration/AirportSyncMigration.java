package com.admin.common.migration;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AirportSyncMigration {
    private final JdbcTemplate jdbc;
    public AirportSyncMigration(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct public void migrate() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS node_airport_sync (node_id BIGINT NOT NULL PRIMARY KEY, "
                +"destination_key CHAR(64) NOT NULL, enabled INT NOT NULL DEFAULT 0, payload TEXT NOT NULL, "
                +"UNIQUE KEY uq_airport_destination (destination_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
