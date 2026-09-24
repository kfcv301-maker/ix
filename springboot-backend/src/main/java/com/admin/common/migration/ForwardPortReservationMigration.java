package com.admin.common.migration;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * Backfills a unique node/port claim for existing forwards after multi-ingress
 * rows have been created. New claims are written before GOST configuration is
 * changed, so two concurrent create requests cannot select the same port.
 */
@Component
@DependsOn("tunnelEntryNodeMigration")
public class ForwardPortReservationMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS forward_port_reservation ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "node_id BIGINT NOT NULL,"
                + "port INT NOT NULL,"
                + "forward_id BIGINT NOT NULL,"
                + "endpoint VARCHAR(16) NOT NULL,"
                + "created_time BIGINT NOT NULL,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_forward_port_reservation_node_port (node_id, port),"
                + "KEY idx_forward_port_reservation_forward (forward_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        // The relation table includes every legacy in_node_id after its own
        // migration. INSERT IGNORE intentionally preserves the oldest claim
        // if a historic database already contains a conflicting port pair;
        // it never changes a live forward while upgrading.
        jdbcTemplate.update("INSERT IGNORE INTO forward_port_reservation "
                + "(node_id, port, forward_id, endpoint, created_time) "
                + "SELECT ten.node_id, f.in_port, f.id, 'ingress', f.created_time "
                + "FROM `forward` f INNER JOIN tunnel_entry_node ten ON ten.tunnel_id = f.tunnel_id "
                + "WHERE f.in_port IS NOT NULL");
        jdbcTemplate.update("INSERT IGNORE INTO forward_port_reservation "
                + "(node_id, port, forward_id, endpoint, created_time) "
                + "SELECT t.out_node_id, f.out_port, f.id, 'egress', f.created_time "
                + "FROM `forward` f INNER JOIN tunnel t ON t.id = f.tunnel_id "
                + "WHERE t.type = 2 AND t.out_node_id IS NOT NULL AND f.out_port IS NOT NULL");
    }
}
