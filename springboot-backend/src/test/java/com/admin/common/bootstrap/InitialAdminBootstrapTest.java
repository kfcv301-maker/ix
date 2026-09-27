package com.admin.common.bootstrap;

import com.admin.common.utils.Md5Util;
import com.admin.common.utils.PasswordHashes;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InitialAdminBootstrapTest {
    @TempDir Path directory;

    private InitialAdminBootstrap bootstrap(UserMapper mapper, String user, String password) {
        InitialAdminBootstrap result = new InitialAdminBootstrap();
        ReflectionTestUtils.setField(result, "userMapper", mapper);
        ReflectionTestUtils.setField(result, "configuredUsername", user);
        ReflectionTestUtils.setField(result, "configuredPassword", password);
        ReflectionTestUtils.setField(result, "credentialsFile", directory.resolve("credentials").toString());
        return result;
    }

    @Test void oldUpdaterWithoutEnvironmentGetsOnePersistentRandomCredential() throws Exception {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.insert(any(User.class))).thenReturn(1);
        bootstrap(mapper, "", "").initialize();
        String original = Files.readString(directory.resolve("credentials"));
        bootstrap(mapper, "", "").initialize();
        assertEquals(original, Files.readString(directory.resolve("credentials")));
        Properties credentials = new Properties();
        try (var input = Files.newInputStream(directory.resolve("credentials"))) { credentials.load(input); }
        ArgumentCaptor<User> writes = ArgumentCaptor.forClass(User.class);
        verify(mapper, times(2)).insert(writes.capture());
        for (User created : writes.getAllValues()) {
            assertEquals(credentials.getProperty("username"), created.getUser());
            assertTrue(PasswordHashes.matches(credentials.getProperty("password"), created.getPwd()));
        }
        assertEquals(48, credentials.getProperty("password").length());
    }

    @Test void legacyAccountIsRenamedToTheDisplayedCredentialWithoutWritingQuotaFields() {
        UserMapper mapper = mock(UserMapper.class);
        User legacy = new User(); legacy.setId(1L); legacy.setUser("admin_user"); legacy.setPwd(Md5Util.md5("admin_user"));
        when(mapper.selectList(any())).thenReturn(List.of(legacy));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        bootstrap(mapper, "admin", "new-password-123456").initialize();
        ArgumentCaptor<UpdateWrapper<User>> write = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), write.capture());
        assertTrue(write.getValue().getSqlSet().contains("user="));
        assertFalse(write.getValue().getSqlSet().contains("in_flow"));
        assertTrue(write.getValue().getParamNameValuePairs().containsValue("admin"));
    }

    @Test void existingUsernameCollisionIsExplicitlyRejected() {
        UserMapper mapper = mock(UserMapper.class);
        User legacy = new User(); legacy.setId(1L); legacy.setUser("admin_user"); legacy.setPwd(Md5Util.md5("admin_user"));
        when(mapper.selectList(any())).thenReturn(List.of(legacy));
        when(mapper.selectCount(any())).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> bootstrap(mapper, "admin", "new-password-123456").initialize());
        verify(mapper, never()).update(isNull(), any(UpdateWrapper.class));
    }
}
