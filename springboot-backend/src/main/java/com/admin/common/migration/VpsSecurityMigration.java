package com.admin.common.migration;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/** Adds non-destructive VPS security state to installations created before it existed. */
@Component
@DependsOn("vpsHostingMigration")
public class VpsSecurityMigration {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("vps_host", "ssh_fingerprint_verified",
                "ALTER TABLE vps_host ADD COLUMN ssh_fingerprint_verified TINYINT NOT NULL DEFAULT 0 AFTER ssh_fingerprint");
        addColumnIfMissing("vps_deployment_task", "active_lock",
                "ALTER TABLE vps_deployment_task ADD COLUMN active_lock VARCHAR(64) NULL AFTER task_status");

        // Old queued/running rows predate the durable lock. Mark them terminal
        // instead of allowing an unknown command to resume after an upgrade.
        long now = System.currentTimeMillis();
        jdbcTemplate.update("UPDATE vps_deployment_task SET task_status = 'failed', finished_time = ?, "
                        + "updated_time = ?, output_log = CONCAT(COALESCE(output_log, ''), ?) "
                        + "WHERE status = 1 AND task_status IN ('pending', 'running') AND active_lock IS NULL",
                now, now, "\n系统升级已安全取消旧的未完成部署任务，请重新确认后再发起。\n");
        ensureIndex();
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (count != null && count == 0) jdbcTemplate.execute(ddl);
    }

    private void ensureIndex() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'vps_deployment_task' "
                        + "AND index_name = 'uk_vps_task_active_lock'",
                Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.execute("CREATE UNIQUE INDEX uk_vps_task_active_lock ON vps_deployment_task (active_lock)");
        }
    }
}
