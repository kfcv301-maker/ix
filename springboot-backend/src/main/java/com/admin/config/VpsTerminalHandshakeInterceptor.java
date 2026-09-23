package com.admin.config;

import com.admin.common.utils.JwtUtil;
import com.admin.service.VpsHostService;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;

/** Authorizes a VPS terminal before a WebSocket is upgraded. */
@Component
public class VpsTerminalHandshakeInterceptor extends HttpSessionHandshakeInterceptor {

    @Resource
    private VpsHostService vpsHostService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) return false;
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        String token = servletRequest.getParameter("secret");
        String hostIdValue = servletRequest.getParameter("vpsId");
        if (!JwtUtil.validateToken(token)) return false;
        Long hostId;
        try {
            hostId = Long.valueOf(hostIdValue);
        } catch (RuntimeException exception) {
            return false;
        }
        Long userId = JwtUtil.getUserIdFromToken(token);
        boolean administrator = Objects.equals(JwtUtil.getRoleIdFromToken(token), 0);
        if (!vpsHostService.canOperate(userId, administrator, hostId)) return false;

        attributes.put("vpsId", hostId);
        attributes.put("userId", userId);
        attributes.put("administrator", administrator);
        return true;
    }
}
