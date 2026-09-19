package com.admin.common.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * Adds persistent node-install preferences without rebuilding or rewriting the
 * existing node table. Existing nodes remain compatible: balanced TCP tuning
 * is selected and DDNS stays disabled until an administrator edits the node.
 */
@Component
public class NodeInstallSettingsMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("tcp_tuning_profile", "VARCHAR(20) NOT NULL DEFAULT 'balanced'");
        addColumnIfMissing("ddns_enabled", "TINYINT NOT NULL DEFAULT 0");
        addColumnIfMissing("ddns_token", "LONGTEXT NULL");
        addColumnIfMissing("ddns_record_name", "VARCHAR(253) NULL");
        jdbcTemplate.update("UPDATE node SET tcp_tuning_profile = 'balanced' "
                + "WHERE tcp_tuning_profile IS NULL OR tcp_tuning_profile = ''");
    }

    private void addColumnIfMissing(String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'node' AND column_name = ?",
                Integer.class, columnName);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE node ADD COLUMN " + columnName + " " + definition);
        }
    }
}
