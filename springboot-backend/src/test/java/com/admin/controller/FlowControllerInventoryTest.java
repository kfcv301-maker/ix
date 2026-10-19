package com.admin.controller;

import com.admin.common.dto.GostConfigDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlowControllerInventoryTest {
    private FlowController controller;
    private NodeService nodes;
    private CheckGostConfigAsync sync;
    private MockHttpServletRequest request;

    @BeforeEach void setup() {
        controller = new FlowController();
        nodes = mock(NodeService.class);
        sync = mock(CheckGostConfigAsync.class);
        ReflectionTestUtils.setField(controller, "nodeService", nodes);
        ReflectionTestUtils.setField(controller, "checkGostConfigAsync", sync);
        Node node = new Node(); node.setId(18L);
        when(nodes.getOne(any(QueryWrapper.class))).thenReturn(node);
        request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test-node-only");
    }

    private GostConfigDto report(String body) {
        assertEquals("ok", controller.config(body, null, request));
        ArgumentCaptor<GostConfigDto> config = ArgumentCaptor.forClass(GostConfigDto.class);
        verify(sync).cleanNodeConfigs(eq("18"), config.capture());
        return config.getValue();
    }

    @Test void legacyFreshAgentNullRegistriesAreKnownEmpty() {
        GostConfigDto config = report("{\"services\":null,\"chains\":null,\"limiters\":null}");
        assertEquals(java.util.List.of(), config.getServices());
        assertEquals(java.util.List.of(), config.getChains());
        assertEquals(java.util.List.of(), config.getLimiters());
    }

    @Test void actualLegacyGostFreshSnapshotWithOmittedChainsAndLimitersRestores() {
        request.addHeader("User-Agent", "Config-Reporter/1.0");
        GostConfigDto config = report("{\"services\":null}");
        assertEquals(java.util.List.of(), config.getServices());
        assertEquals(java.util.List.of(), config.getChains());
        assertEquals(java.util.List.of(), config.getLimiters());
    }

    @Test void legacyReporterMustStillProvideServicesField() {
        request.addHeader("User-Agent", "Config-Reporter/1.0");
        GostConfigDto config = report("{}");
        assertNull(config.getServices());
    }

    @Test void wrappedLegacyFreshInventoryAlsoRestores() {
        GostConfigDto config = report("{\"config\":{\"services\":null,\"chains\":null,\"limiters\":null}}");
        assertNotNull(config.getServices());
        assertNotNull(config.getChains());
        assertNotNull(config.getLimiters());
    }

    @Test void populatedServiceIsPreservedWhileKnownEmptyRegistriesNormalize() {
        GostConfigDto config = report("{\"services\":[{\"name\":\"12_7_9_tcp\"}],\"chains\":null,\"limiters\":null}");
        assertEquals("12_7_9_tcp", config.getServices().getFirst().getName());
        assertEquals(java.util.List.of(), config.getChains());
        assertEquals(java.util.List.of(), config.getLimiters());
    }

    @Test void omittedFieldsRemainUnknownSoPartialReportsCannotRecreateRules() {
        GostConfigDto config = report("{\"services\":null}");
        assertNull(config.getServices());
        assertNull(config.getChains());
        assertNull(config.getLimiters());
    }

    @Test void unrecognizedNodeCannotTriggerReconciliation() {
        when(nodes.getOne(any(QueryWrapper.class))).thenReturn(null);
        assertEquals("ok", controller.config("{\"services\":null,\"chains\":null,\"limiters\":null}", null, request));
        verifyNoInteractions(sync);
    }
}
