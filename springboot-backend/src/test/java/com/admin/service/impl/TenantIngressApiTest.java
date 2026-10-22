package com.admin.service.impl;

import com.admin.common.dto.ForwardWithTunnelDto;
import com.admin.common.dto.TunnelListDto;
import com.admin.common.dto.UserPackageDto;
import com.admin.common.service.UserIngressAddressService;
import com.admin.common.task.ForwardSyncTaskService;
import com.admin.common.task.TunnelForwardDiagnosisTaskService;
import com.admin.common.utils.JwtUtil;
import com.admin.common.lang.R;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.StatisticsFlowService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantIngressApiTest {
    private static final String RAW = "underlying.example.com";
    private static final String CUSTOM = "alice.example.com";

    @Test void tunnelListAndSelectorBothExposeOnlyTheViewersCustomDomain() {
        try (var jwt = jwt(1)) {
            TunnelServiceImpl service = tunnels();
            Tunnel tunnel = tunnel();
            doReturn(new ArrayList<>(List.of(tunnel))).when(service).list(any(Wrapper.class));
            List<Tunnel> list = (List<Tunnel>) service.getAllTunnels().getData();
            assertEquals(CUSTOM, list.get(0).getInIp());
            assertNull(list.get(0).getOutIp()); assertNull(list.get(0).getTcpListenAddr());
            assertNull(list.get(0).getUdpListenAddr()); assertNull(list.get(0).getInterfaceName());
            assertEquals("CUSTOM", list.get(0).getEntryAddressMode());
            assertFalse(JSON.toJSONString(list).contains(RAW));

            doReturn(new ArrayList<>(List.of(tunnel()))).when(service).list(any(Wrapper.class));
            List<TunnelListDto> selector = (List<TunnelListDto>) service.userTunnel().getData();
            assertEquals(CUSTOM, selector.get(0).getIp());
            assertNull(selector.get(0).getOriginalIp());
            assertFalse(JSON.toJSONString(selector).contains(RAW));
        }
    }

    @Test void invalidCustomDomainDoesNotLeakAndRevokedTunnelDisappearsFromBothLists() {
        try (var jwt = jwt(1)) {
            TunnelServiceImpl service = tunnels();
            UserIngressAddressService addresses = (UserIngressAddressService) ReflectionTestUtils.getField(service, "userIngressAddressService");
            when(addresses.forUser(7)).thenReturn(Map.of(11, new UserIngressAddressService.Address("CUSTOM", null, null, 5L)));
            doAnswer(call -> new ArrayList<>(List.of(tunnel()))).when(service).list(any(Wrapper.class));
            String list = JSON.toJSONString(service.getAllTunnels());
            assertFalse(list.contains(RAW)); assertTrue(list.contains("CUSTOM"));
            assertFalse(JSON.toJSONString(service.userTunnel()).contains(RAW));
            when(addresses.forUser(7)).thenReturn(Map.of());
            assertTrue(((List<?>) service.getAllTunnels().getData()).isEmpty());
            assertTrue(((List<?>) service.userTunnel().getData()).isEmpty());
        }
    }

    @Test void administratorsAndTunnelOwnersKeepManagementAddresses() {
        TunnelServiceImpl service = tunnels();
        try (var jwt = jwt(0)) {
            doReturn(new ArrayList<>(List.of(tunnel()))).when(service).list();
            assertTrue(JSON.toJSONString(service.getAllTunnels()).contains(RAW));
        }
        try (var jwt = jwt(1)) {
            Tunnel owned = tunnel(); owned.setOwnerUserId(7L);
            doReturn(new ArrayList<>(List.of(owned))).when(service).list(any(Wrapper.class));
            assertTrue(JSON.toJSONString(service.getAllTunnels()).contains(RAW));
            assertTrue(owned.getCanManage());
        }
    }

    @Test void forwardListProjectsCustomEntryAndHidesSyncErrorInfrastructureDetails() {
        ForwardServiceImpl service = new ForwardServiceImpl();
        ForwardMapper mapper = mock(ForwardMapper.class);
        ForwardSyncTaskService tasks = mock(ForwardSyncTaskService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "forwardSyncTaskService", tasks);
        ReflectionTestUtils.setField(service, "userIngressAddressService", addresses());
        ForwardWithTunnelDto forward = new ForwardWithTunnelDto();
        forward.setId(5L); forward.setTunnelId(11); forward.setInIp(RAW);
        forward.setInterfaceName(RAW); forward.setSyncError("dial " + RAW + " failed");
        when(mapper.selectForwardsWithTunnelByUserId(7)).thenReturn(List.of(forward));
        when(tasks.activeSummaries(any())).thenReturn(Map.of());
        try (var jwt = jwt(1)) {
            String response = JSON.toJSONString(service.getAllForwards());
            assertTrue(response.contains(CUSTOM)); assertFalse(response.contains(RAW));
            assertEquals("CUSTOM", forward.getEntryAddressMode());
        }
        forward.setInIp(RAW);
        when(mapper.selectAllForwardsWithTunnel()).thenReturn(List.of(forward));
        try (var jwt = jwt(0)) { assertTrue(JSON.toJSONString(service.getAllForwards()).contains(RAW)); }
    }

    @Test void dashboardPackageUsesTheSameAssignedAddressAsTheForwardAndTunnelLists() {
        UserServiceImpl service = spy(new UserServiceImpl());
        UserMapper mapper = mock(UserMapper.class);
        StatisticsFlowService statistics = mock(StatisticsFlowService.class);
        ReflectionTestUtils.setField(service, "userMapper", mapper);
        ReflectionTestUtils.setField(service, "statisticsFlowService", statistics);
        ReflectionTestUtils.setField(service, "userIngressAddressService", addresses());
        User user = new User(); user.setId(7L); user.setRoleId(1);
        doReturn(user).when(service).getById(7);
        UserPackageDto.UserForwardDetailDto forward = new UserPackageDto.UserForwardDetailDto();
        forward.setTunnelId(11); forward.setInIp(RAW);
        when(mapper.getUserForwardDetails(7)).thenReturn(List.of(forward));
        when(mapper.getUserTunnelDetails(7)).thenReturn(List.of());
        when(statistics.list(any(Wrapper.class))).thenReturn(List.of());
        try (var jwt = jwt(1)) {
            R result = service.getUserPackageInfo(); assertEquals(0, result.getCode());
            String response = JSON.toJSONString(result);
            assertFalse(response.contains(RAW)); assertTrue(response.contains(CUSTOM));
            assertEquals("CUSTOM", forward.getEntryAddressMode());
        }
    }

    @Test void diagnosisTaskReadCannotBypassTheAddressBoundary() {
        TunnelServiceImpl service = tunnels();
        TunnelForwardDiagnosisTaskService tasks = mock(TunnelForwardDiagnosisTaskService.class);
        ReflectionTestUtils.setField(service, "tunnelForwardDiagnosisTaskService", tasks);
        doReturn(tunnel()).when(service).getById(11L);
        R report = R.ok(Map.of("tunnelId", 11, "forwards", List.of(Map.of("results", List.of(
                Map.of("targetIp", RAW, "targetPort", 1000, "nodeName", RAW, "description", "入口->出口", "success", true))))));
        when(tasks.get("task", 7, false)).thenReturn(report);
        when(tasks.get("task", 7, true)).thenReturn(report);
        try (var jwt = jwt(1)) { assertFalse(JSON.toJSONString(service.getTunnelForwardDiagnosisTask("task")).contains(RAW)); }
        try (var jwt = jwt(0)) { assertTrue(JSON.toJSONString(service.getTunnelForwardDiagnosisTask("task")).contains(RAW)); }
    }

    @Test void sharedNodeOperationErrorsNeverReturnAgentAddressesOrErrorPayloads() {
        ForwardServiceImpl service = new ForwardServiceImpl();
        R error = R.err(-1, "dial " + RAW + " failed"); error.setData(Map.of("address", RAW));
        Tunnel shared = tunnel();
        try (var jwt = jwt(1)) {
            R projected = ReflectionTestUtils.invokeMethod(service, "nodeOperationView", error, shared);
            assertEquals(-1, projected.getCode()); assertNull(projected.getData());
            assertFalse(JSON.toJSONString(projected).contains(RAW));
            shared.setOwnerUserId(7L);
            assertSame(error, ReflectionTestUtils.invokeMethod(service, "nodeOperationView", error, shared));
        }
        shared.setOwnerUserId(null);
        try (var jwt = jwt(0)) { assertSame(error, ReflectionTestUtils.invokeMethod(service, "nodeOperationView", error, shared)); }
    }

    private TunnelServiceImpl tunnels() {
        TunnelServiceImpl service = spy(new TunnelServiceImpl());
        UserTunnelMapper grants = mock(UserTunnelMapper.class);
        TunnelEntryNodeMapper entries = mock(TunnelEntryNodeMapper.class);
        UserTunnel grant = new UserTunnel(); grant.setTunnelId(11); grant.setUserId(7); grant.setStatus(1);
        when(grants.selectList(any())).thenReturn(List.of(grant));
        when(entries.selectList(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "userTunnelMapper", grants);
        ReflectionTestUtils.setField(service, "tunnelEntryNodeMapper", entries);
        ReflectionTestUtils.setField(service, "userIngressAddressService", addresses());
        return service;
    }
    private UserIngressAddressService addresses() {
        UserIngressAddressService addresses = mock(UserIngressAddressService.class);
        when(addresses.forUser(7)).thenReturn(Map.of(11, new UserIngressAddressService.Address("CUSTOM", CUSTOM, CUSTOM, 5L)));
        return addresses;
    }
    private static Tunnel tunnel() {
        Tunnel tunnel = new Tunnel(); tunnel.setId(11L); tunnel.setStatus(1); tunnel.setInIp(RAW); tunnel.setOutIp(RAW);
        tunnel.setTcpListenAddr(RAW); tunnel.setUdpListenAddr(RAW); tunnel.setInterfaceName(RAW); return tunnel;
    }
    private static MockedStatic<JwtUtil> jwt(int role) {
        MockedStatic<JwtUtil> jwt = mockStatic(JwtUtil.class);
        jwt.when(JwtUtil::getUserIdFromToken).thenReturn(7);
        jwt.when(JwtUtil::getRoleIdFromToken).thenReturn(role);
        return jwt;
    }
}
