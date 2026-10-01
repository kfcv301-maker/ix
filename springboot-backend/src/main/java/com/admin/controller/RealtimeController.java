package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.common.utils.HttpContextUtils;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.admin.service.RealtimeTicketService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Issues one-time tickets for the browser-only monitoring socket. */
@RestController
@RequestMapping("/api/v1/realtime")
public class RealtimeController {

    @Resource
    private RealtimeTicketService realtimeTicketService;

    @Resource
    private UserMapper userMapper;

    @LogAnnotation
    @PostMapping("/ticket")
    public R ticket() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        RealtimeTicketService.IssuedTicket ticket = realtimeTicketService.issue(JwtUtil.getUserIdFromToken(token));
        Map<String, Object> response = new HashMap<>();
        response.put("ticket", ticket.getValue());
        response.put("expiresAt", ticket.getExpiresAt());
        return R.ok(response);
    }

    /** Dashboard labels only; this endpoint never exposes node credentials. */
    @LogAnnotation
    @PostMapping("/nodes")
    public R nodes() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        Long userId = JwtUtil.getUserIdFromToken(token);
        User user = userMapper.selectById(userId);
        if (user == null || !Objects.equals(user.getStatus(), 1)) {
            return R.err(403, "当前用户不可查看实时监控");
        }
        return R.ok(Objects.equals(user.getRoleId(), 0)
                ? userMapper.getAllRealtimeNodes()
                : userMapper.getRealtimeNodes(userId, System.currentTimeMillis()));
    }
}
