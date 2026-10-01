package com.admin.config;

import com.admin.common.dto.RealtimeNodeDto;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.admin.service.RealtimeTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketAssignedNodeAccessTest {
    @Test
    void summaryReceivesAssignedNodesButDetailRemainsOwnerOnly() throws Exception {
        UserMapper userMapper = mock(UserMapper.class);
        RealtimeTicketService tickets = mock(RealtimeTicketService.class);
        BrowserWebSocketOriginPolicy originPolicy = mock(BrowserWebSocketOriginPolicy.class);
        User user = new User();
        user.setId(42L);
        user.setStatus(1);
        user.setRoleId(1);
        when(userMapper.selectById(42L)).thenReturn(user);
        when(userMapper.getAccessibleNodeIds(42L)).thenReturn(List.of(1L));
        when(userMapper.getRealtimeNodes(eq(42L), anyLong())).thenReturn(List.of(node(1L), node(2L)));
        when(userMapper.getNextRealtimeGrantExpiry(eq(42L), anyLong())).thenReturn(Long.MAX_VALUE);
        when(tickets.consume("ticket")).thenReturn(new RealtimeTicketService.Access(42L, Long.MAX_VALUE));
        when(originPolicy.isAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        WebSocketInterceptor interceptor = new WebSocketInterceptor();
        ReflectionTestUtils.setField(interceptor, "userMapper", userMapper);
        ReflectionTestUtils.setField(interceptor, "realtimeTicketService", tickets);
        ReflectionTestUtils.setField(interceptor, "browserWebSocketOriginPolicy", originPolicy);

        Map<String, Object> summary = handshake(interceptor, "summary");
        assertEquals("summary", summary.get("monitorMetrics"));
        assertEquals(Set.of(1L, 2L), summary.get("allowedNodeIds"));
        assertEquals(Long.MAX_VALUE, summary.get("monitorAccessExpiresAt"));

        Map<String, Object> detail = handshake(interceptor, "detail");
        assertEquals("detail", detail.get("monitorMetrics"));
        assertEquals(Set.of(1L), detail.get("allowedNodeIds"));
    }

    private static Map<String, Object> handshake(WebSocketInterceptor interceptor, String metrics) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("type", "2");
        request.setParameter("ticket", "ticket");
        request.setParameter("metrics", metrics);
        Map<String, Object> attributes = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class), attributes));
        return attributes;
    }

    private static RealtimeNodeDto node(Long id) {
        RealtimeNodeDto node = new RealtimeNodeDto();
        node.setId(id);
        node.setName("node " + id);
        return node;
    }
}
