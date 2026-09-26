package com.admin.common.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * Adds the tunnel-domain pool and per-user entry selection without touching
 * any node, DDNS, or forwarding tables/configuration.
 */
@Component
public class TunnelEntryDomainMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS tunnel_entry_domain ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "tunnel_id INT NOT NULL,"
                + "domain VARCHAR(253) NOT NULL,"
                + "is_default TINYINT NOT NULL DEFAULT 0,"
                + "created_time BIGINT NOT NULL,"
                + "updated_time BIGINT NULL,"
                + "status INT NOT NULL DEFAULT 1,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_tunnel_entry_domain (tunnel_id, domain),"
                + "KEY idx_tunnel_entry_domain_tunnel (tunnel_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        addColumnIfMissing("entry_address_mode", "VARCHAR(16) NOT NULL DEFAULT 'NONE'");
        addColumnIfMissing("entry_domain_id", "BIGINT NULL");
        addIndexIfMissing("idx_user_tunnel_entry_domain", "entry_domain_id");
        jdbcTemplate.update("UPDATE user_tunnel SET entry_address_mode = 'NONE' "
                + "WHERE entry_address_mode IS NULL OR entry_address_mode = ''");
    }

    private void addColumnIfMissing(String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'user_tunnel' AND column_name = ?",
                Integer.class, columnName);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE user_tunnel ADD COLUMN " + columnName + " " + definition);
        }
    }

    private void addIndexIfMissing(String indexName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'user_tunnel' AND index_name = ?",
                Integer.class, indexName);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE user_tunnel ADD KEY " + indexName + " (" + columnName + ")");
        }
    }
}
