package com.admin.controller;

import com.admin.common.security.LoginAttemptLimiter;
import com.admin.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoginRateLimitControllerTest {
    @Test void throttledRequestsDoNotAttemptPasswordVerification() throws Exception {
        UserController controller = new UserController();
        UserService users = mock(UserService.class);
        LoginAttemptLimiter limiter = mock(LoginAttemptLimiter.class);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "loginAttemptLimiter", limiter);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/user/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"test-password\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value(429));
        verifyNoInteractions(users);
    }
}
