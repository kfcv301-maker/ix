package com.admin.controller;

import com.admin.common.service.*;
import com.admin.entity.Node;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NodeAddressObservationControllerTest {
    final NodeAgentConfigService configs=mock(NodeAgentConfigService.class);
    final AirportSyncService sync=mock(AirportSyncService.class);
    final NodeAddressObservationController controller=new NodeAddressObservationController(configs,sync);
    @Test void unauthenticatedRequestCannotInspectOrPostAnyNode() {
        var request=new MockHttpServletRequest();
        assertEquals(401,controller.policy(request).getStatusCode().value());
        assertEquals(401,controller.report(request,null).getStatusCode().value());verifyNoInteractions(sync);
    }
    @Test void bindingIdentityComesOnlyFromAuthenticatedNodeAndReceiptIncludesExactIp() {
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer fixture");
        Node node=new Node();node.setId(26L);when(configs.authenticatedNode("fixture")).thenReturn(node);
        when(sync.observationPolicy(26L)).thenReturn(Map.of("enabled",true,"sourceMode","aws-imds"));
        assertEquals(Map.of("code",0,"data",Map.of("enabled",true,"sourceMode","aws-imds")),controller.policy(request).getBody());
        var report=new AirportSyncService.Report("8.8.4.4","i-1234567890abcdef0",1800000000L,"11111111-1111-1111-1111-111111111111");
        when(sync.report(node,report)).thenReturn(true);
        assertEquals(Map.of("code",0,"data",Map.of("accepted",true,"ip","8.8.4.4")),controller.report(request,report).getBody());
        when(sync.report(node,report)).thenReturn(false);assertEquals(403,controller.report(request,report).getStatusCode().value());
    }
}
