package com.admin.controller;

import com.admin.service.NodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeInstallBootstrapControllerTest {
    private static final String TOKEN = "B".repeat(32);
    private NodeService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(NodeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NodeInstallBootstrapController(service)).build();
    }

    @Test
    void downloadIsPlainTextAndCannotBeCachedOrSniffed() throws Exception {
        when(service.claimInstallScript(TOKEN)).thenReturn("#!/usr/bin/env bash\nset -eu\n");
        mvc.perform(get("/api/v1/node/bootstrap/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store, private, max-age=0"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        verify(service).claimInstallScript(TOKEN);
    }

    @Test
    void missingExpiredAndAlreadyUsedCapabilitiesReturnGone() throws Exception {
        mvc.perform(get("/api/v1/node/bootstrap/" + TOKEN)).andExpect(status().isGone());
    }

    @Test
    void headAndPostDoNotConsumeCapability() throws Exception {
        mvc.perform(head("/api/v1/node/bootstrap/" + TOKEN)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/node/bootstrap/" + TOKEN)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    @Test
    void permanentCommandUsesHeaderCredentialAndSupportsRepeatedDownloads() throws Exception {
        String capability = "A".repeat(43);
        when(service.getReusableInstallScript(1L, 2L, "https://panel.example.com", capability))
                .thenReturn("#!/usr/bin/env bash\nset -eu\n");
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/node/install-script/1")
                    .param("issuerId", "2").param("panelUrl", "https://panel.example.com")
                    .header("Authorization", "Bearer " + capability))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store, private, max-age=0"));
        }
        verify(service, times(2)).getReusableInstallScript(1L, 2L, "https://panel.example.com", capability);
    }

    @Test
    void permanentEndpointRejectsMissingCredentialAndHead() throws Exception {
        mvc.perform(get("/api/v1/node/install-script/1").param("issuerId", "2").param("panelUrl", "https://panel.example.com"))
                .andExpect(status().isForbidden());
        mvc.perform(head("/api/v1/node/install-script/1").param("issuerId", "2").param("panelUrl", "https://panel.example.com"))
                .andExpect(status().isMethodNotAllowed());
    }
}
