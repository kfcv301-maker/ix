package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.common.task.TunnelForwardDiagnosisTaskService;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.ForwardService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TunnelForwardDiagnosisAuthorizationTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void largerAuthorizedSelectionReachesOneTaskAndQueryRemainsRestrictedToCurrentUser() {
        TunnelServiceImpl service = spy(new TunnelServiceImpl());
        ForwardService forwards = mock(ForwardService.class);
        UserTunnelMapper grants = mock(UserTunnelMapper.class);
        TunnelForwardDiagnosisTaskService tasks = mock(TunnelForwardDiagnosisTaskService.class);
        ReflectionTestUtils.setField(service, "forwardService", forwards);
        ReflectionTestUtils.setField(service, "userTunnelMapper", grants);
        ReflectionTestUtils.setField(service, "tunnelForwardDiagnosisTaskService", tasks);
        Tunnel tunnel = new Tunnel(); tunnel.setId(11L); tunnel.setOwnerUserId(7L);
        doReturn(tunnel).when(service).getById(11L);
        List<Forward> selected = LongStream.rangeClosed(1, 45).mapToObj(id -> {
            Forward forward = new Forward(); forward.setId(id); forward.setUserId(7); forward.setTunnelId(11); return forward;
        }).toList();
        when(forwards.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(selected);
        when(grants.selectOne(any())).thenReturn(new UserTunnel());
        when(tasks.start(tunnel, 7, selected)).thenReturn(R.ok());
        try (var jwt = mockStatic(JwtUtil.class)) {
            jwt.when(JwtUtil::getUserIdFromToken).thenReturn(7);
            jwt.when(JwtUtil::getRoleIdFromToken).thenReturn(1);
            assertEquals(0, service.diagnoseTunnelForwards(11L).getCode());
            ArgumentCaptor<QueryWrapper<Forward>> query = ArgumentCaptor.forClass(QueryWrapper.class);
            verify(forwards).list(query.capture());
            assertTrue(query.getValue().getSqlSegment().contains("user_id"));
            assertTrue(query.getValue().getParamNameValuePairs().containsValue(7));
            verify(tasks).start(tunnel, 7, selected);
            clearInvocations(forwards, tasks);
            tunnel.setOwnerUserId(8L);
            assertEquals(403, service.diagnoseTunnelForwards(11L).getCode());
            verifyNoInteractions(forwards, tasks);
            tunnel.setOwnerUserId(null); when(grants.selectOne(any())).thenReturn(null);
            assertEquals(403, service.diagnoseTunnelForwards(11L).getCode());
            verifyNoInteractions(forwards, tasks);
        }
    }
}
