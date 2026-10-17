package com.admin.common.migration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ForwardProxyProtocolMigrationTest {
    @Test void oldDatabaseGetsANonNullDisabledDefault() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        ForwardProxyProtocolMigration migration = new ForwardProxyProtocolMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbc);
        migration.migrate();
        verify(jdbc).execute("ALTER TABLE `forward` ADD COLUMN `proxy_protocol` INT NOT NULL DEFAULT 0");
        verify(jdbc, never()).update(anyString());
    }

    @Test void restartNeverResetsAnExistingProxyVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        ForwardProxyProtocolMigration migration = new ForwardProxyProtocolMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbc);
        migration.migrate(); migration.migrate();
        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).update(anyString());
    }
}
