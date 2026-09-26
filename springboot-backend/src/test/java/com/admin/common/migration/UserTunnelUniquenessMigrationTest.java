package com.admin.common.migration;

import com.admin.entity.UserTunnel;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class UserTunnelUniquenessMigrationTest {
    private UserTunnel row(int id, long inbound, long outbound) {
        UserTunnel grant = new UserTunnel(); grant.setId(id); grant.setUserId(3); grant.setTunnelId(42);
        grant.setInFlow(inbound); grant.setOutFlow(outbound); return grant;
    }
    @Test void originalsAndAliasesAreSavedBeforeCountersAreMergedAndRowsRemoved() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbc.query(startsWith("SELECT user_id"), any(RowMapper.class))).thenReturn(List.of(row(8, 0, 0)));
        when(jdbc.query(startsWith("SELECT *"), any(RowMapper.class), eq(3), eq(42)))
                .thenReturn(List.of(row(8, 100, 200), row(9, 300, 400)));
        UserTunnelUniquenessMigration migration = new UserTunnelUniquenessMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(migration, "transactionManager", manager);
        migration.migrate();
        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).update(startsWith("INSERT IGNORE INTO user_tunnel_duplicate_archive"), eq(3), eq(42));
        ordered.verify(jdbc).update(startsWith("INSERT INTO user_tunnel_alias"), eq(9), eq(3), eq(42), eq(9));
        ordered.verify(jdbc).update(eq("UPDATE user_tunnel SET in_flow = ?, out_flow = ? WHERE id = ?"), eq(400L), eq(600L), eq(9));
        ordered.verify(jdbc).update(startsWith("DELETE FROM user_tunnel"), eq(3), eq(42), eq(9));
        verify(manager).commit(any());
        verify(jdbc).execute(startsWith("CREATE UNIQUE INDEX"));
    }
    @Test void counterOverflowRollsBackWithoutDeletingAnything() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbc.query(startsWith("SELECT user_id"), any(RowMapper.class))).thenReturn(List.of(row(8, 0, 0)));
        when(jdbc.query(startsWith("SELECT *"), any(RowMapper.class), eq(3), eq(42)))
                .thenReturn(List.of(row(8, Long.MAX_VALUE, 0), row(9, 1, 0)));
        UserTunnelUniquenessMigration migration = new UserTunnelUniquenessMigration();
        ReflectionTestUtils.setField(migration, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(migration, "transactionManager", manager);
        assertThrows(ArithmeticException.class, migration::migrate);
        verify(manager).rollback(any());
        verify(jdbc, never()).update(startsWith("DELETE"), any(), any(), any());
    }
}
