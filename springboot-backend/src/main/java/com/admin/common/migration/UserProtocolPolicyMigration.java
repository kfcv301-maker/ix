package com.admin.common.migration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Existing grants retain unrestricted TLS and enabled native UDP. */
@Component
@DependsOn("tunnelEntryDomainMigration")
public class UserProtocolPolicyMigration {
    @Resource private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        addColumns("user_tunnel");
        Integer archive = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'user_tunnel_duplicate_archive'", Integer.class);
        if (archive != null && archive > 0) addColumns("user_tunnel_duplicate_archive");
    }

    private void addColumns(String table) {
        add(table, "tls_policy", "VARCHAR(16) NOT NULL DEFAULT 'UNRESTRICTED'");
        add(table, "allow_udp", "TINYINT(1) NOT NULL DEFAULT 1");
        add(table, "protocol_sync_pending", "TINYINT(1) NOT NULL DEFAULT 0");
        add(table, "protocol_sync_generation", "VARCHAR(36) NULL");
        add(table, "protocol_sync_error", "VARCHAR(255) NULL");
        add(table, "protocol_policy_revision", "BIGINT NOT NULL DEFAULT 0");
    }

    private void add(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?", Integer.class, table, column);
        if (count != null && count == 0) jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
