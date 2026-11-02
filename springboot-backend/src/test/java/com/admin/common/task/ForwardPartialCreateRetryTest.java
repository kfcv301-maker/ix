package com.admin.common.task;

import com.admin.common.dto.GostDto;
import com.admin.common.service.UserProtocolPolicyService;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.service.UserTunnelService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ForwardPartialCreateRetryTest {
    @ParameterizedTest
    @CsvSource({"tcp,false,false", "udp,false,false", "none,false,false", "tcp,true,false",
            "tcp,false,true", "udp,false,true", "none,false,true", "tcp,true,true"})
    void partialLegacyCreateRecoversOnlyMissingProtocols(String existing, boolean duplicateReply, boolean configurationMissing) {
        ForwardSyncTaskService service = new ForwardSyncTaskService();
        var policies = mock(UserProtocolPolicyService.class);
        var grants = mock(UserTunnelService.class);
        ReflectionTestUtils.setField(service, "userProtocolPolicyService", policies);
        ReflectionTestUtils.setField(service, "userTunnelService", grants);
        when(policies.forForward(any())).thenReturn(JSONObject.of("tls", "UNRESTRICTED", "allowUdp", true));
        Forward forward = new Forward(); forward.setId(12L); forward.setUserId(3); forward.setTunnelId(42);
        forward.setInPort(18000); forward.setRemoteAddr("127.0.0.1:443"); forward.setStrategy("fifo");
        forward.setProxyProtocol(0);
        Tunnel tunnel = new Tunnel(); tunnel.setId(42L); tunnel.setType(1);
        ForwardSyncTask task = new ForwardSyncTask(); task.setNodeId(5L); task.setOperation("resume"); task.setServiceName("12_3_0");
        Set<String> running = new HashSet<>();
        if (!existing.equals("none")) running.add("12_3_0_" + existing);
        List<String> added = new ArrayList<>();
        try (var socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(5L), any(), anyString())).thenAnswer(call -> {
                String command = call.getArgument(2); Object data = call.getArgument(1);
                GostDto result = new GostDto(); result.setMsg("OK");
                if (command.equals("SetUserProtocolPolicy")) { result.setMsg("未知命令类型"); return result; }
                if (command.equals("ResumeService")) {
                    for (Object name : ((JSONObject)data).getJSONArray("services")) {
                        if (!running.contains(name)) { result.setMsg("service " + name + (configurationMissing ? " configuration" : "") + " not found"); break; }
                    }
                } else if (command.equals("AddService")) {
                    for (Object item : (JSONArray)data) {
                        String name = ((JSONObject)item).getString("name");
                        if (running.contains(name)) { result.setMsg("service " + name + " already exists"); break; }
                        running.add(name); added.add(name);
                        // A previous Add succeeded but its ACK was lost; retry reports a duplicate.
                        if (duplicateReply) { result.setMsg("service " + name + " already exists"); break; }
                    }
                } else { fail("Unexpected command: " + command); }
                return result;
            });
            String error = ReflectionTestUtils.invokeMethod(service, "executeIngressName", task, forward, tunnel);
            assertNull(error, "Partial success must not become an endless duplicate-service retry");
            assertEquals(Set.of("12_3_0_tcp", "12_3_0_udp"), running);
            List<String> expected = new ArrayList<>();
            if (!existing.equals("tcp")) expected.add("12_3_0_tcp");
            if (!existing.equals("udp")) expected.add("12_3_0_udp");
            assertEquals(expected, added);
        }
    }
}
