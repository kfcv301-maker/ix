package com.admin.controller;
import com.admin.common.service.NodeAgentConfigService;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeAgentConfigControllerTest {
    private MockMvc mvc;
    private NodeAgentConfigService service;
    @BeforeEach void setup() {
        service = mock(NodeAgentConfigService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NodeAgentConfigController(service)).build();
    }
    @Test void onlyHeaderCredentialIsAcceptedAndResponsesNeverCache() throws Exception {
        when(service.installationConfig(4L, "install-key")).thenReturn(JSONObject.of("schemaVersion", 1));
        mvc.perform(get("/api/v1/node/install-config/4").header("Authorization", "Bearer install-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(header().string("Cache-Control", "no-store, private, max-age=0"));
        mvc.perform(get("/api/v1/node/install-config/4").param("token", "install-key")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/node/runtime-config").header("Authorization", "browser-jwt")).andExpect(status().isUnauthorized());
    }
    @Test void invalidSavedSettingsReturnUnavailableAndHeadDoesNotFetchSecrets() throws Exception {
        when(service.authenticatedRuntimeConfig("node-key")).thenThrow(new IllegalStateException("private details"));
        mvc.perform(get("/api/v1/node/runtime-config").header("Authorization", "Bearer node-key"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private details"))));
        reset(service);
        mvc.perform(head("/api/v1/node/runtime-config")).andExpect(status().isMethodNotAllowed());
        mvc.perform(head("/api/v1/node/install-config/4")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
}
