package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.common.utils.HttpContextUtils;
import com.admin.common.utils.JwtUtil;
import com.admin.service.RealtimeTicketService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/** Issues one-time tickets for the browser-only monitoring socket. */
@RestController
@RequestMapping("/api/v1/realtime")
public class RealtimeController {

    @Resource
    private RealtimeTicketService realtimeTicketService;

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
}
