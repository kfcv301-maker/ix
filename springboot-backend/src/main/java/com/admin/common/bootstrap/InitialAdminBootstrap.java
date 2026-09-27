package com.admin.common.bootstrap;

import com.admin.common.utils.PasswordHashes;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.List;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;
import java.io.IOException;

/** Creates the first administrator from deployment-only environment variables. */
@Component
@DependsOn("userTokenVersionMigration")
public class InitialAdminBootstrap {

    private static final int ACTIVE = 1;
    private static final int ADMIN = 0;
    private static final String LEGACY_USERNAME = "admin_user";
    private static final String LEGACY_PASSWORD = "admin_user";

    @Resource
    private UserMapper userMapper;

    @Value("${panel.initial-admin.username:}")
    private String configuredUsername;

    @Value("${panel.initial-admin.password:}")
    private String configuredPassword;

    @Value("${panel.initial-admin.credentials-file:/app/config/initial-admin-credentials}")
    private String credentialsFile;

    @PostConstruct
    public void initialize() {
        List<User> administrators = userMapper.selectList(new QueryWrapper<User>().eq("role_id", ADMIN));
        if (administrators.isEmpty()) {
            createAdministrator(requireConfiguredCredentials());
            return;
        }

        // An old dump may still contain the former public credential. Refuse
        // to leave it active: the operator must supply a replacement secret.
        for (User user : administrators) {
            if (LEGACY_USERNAME.equals(user.getUser()) && PasswordHashes.matches(LEGACY_PASSWORD, user.getPwd())) {
                Credentials credentials = requireConfiguredCredentials();
                Long conflicts = userMapper.selectCount(new QueryWrapper<User>()
                        .eq("user", credentials.username).ne("id", user.getId()));
                if (conflicts != null && conflicts > 0) {
                    throw new IllegalStateException("初始管理员用户名已被占用，请更换 PANEL_INITIAL_ADMIN_USERNAME");
                }
                // Change only credentials, never a stale snapshot of quota counters.
                if (userMapper.update(null, new UpdateWrapper<User>()
                        .eq("id", user.getId()).eq("pwd", user.getPwd())
                        .set("user", credentials.username)
                        .set("pwd", PasswordHashes.encode(credentials.password))
                        .set("token_version", nextTokenVersion(user.getTokenVersion()))
                        .set("updated_time", System.currentTimeMillis())) != 1) {
                    throw new IllegalStateException("无法替换旧默认管理员密码");
                }
            }
        }
    }

    private void createAdministrator(Credentials credentials) {
        Long conflicts = userMapper.selectCount(new QueryWrapper<User>().eq("user", credentials.username));
        if (conflicts != null && conflicts > 0) {
            throw new IllegalStateException("初始管理员用户名已被占用，请更换 PANEL_INITIAL_ADMIN_USERNAME");
        }
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUser(credentials.username);
        user.setPwd(PasswordHashes.encode(credentials.password));
        user.setTokenVersion(1);
        user.setRoleId(ADMIN);
        user.setExpTime(4_102_444_800_000L);
        user.setFlow(99_999L);
        user.setInFlow(0L);
        user.setOutFlow(0L);
        user.setFlowResetTime(1L);
        user.setNum(99_999);
        user.setCreatedTime(now);
        user.setUpdatedTime(now);
        user.setStatus(ACTIVE);
        if (userMapper.insert(user) != 1) throw new IllegalStateException("无法创建初始管理员");
    }

    private Credentials requireConfiguredCredentials() {
        String username = configuredUsername == null ? "" : configuredUsername.trim();
        String password = configuredPassword == null ? "" : configuredPassword;
        // A still-running old updater cannot add new environment variables.
        // Persist a random bootstrap credential on a private named volume.
        if (username.isEmpty() && password.isEmpty()) {
            return loadOrCreateCredentials();
        }
        if (!username.matches("[A-Za-z0-9_.@-]{3,100}")) {
            throw new IllegalStateException("必须设置 PANEL_INITIAL_ADMIN_USERNAME（3-100 位字母、数字或 ._@-）");
        }
        if (password.length() < 12 || password.length() > 512 || password.equalsIgnoreCase(username)
                || LEGACY_PASSWORD.equals(password)) {
            throw new IllegalStateException("必须设置不少于 12 位且非默认值的 PANEL_INITIAL_ADMIN_PASSWORD");
        }
        return new Credentials(username, password);
    }

    private Credentials loadOrCreateCredentials() {
        try {
            Path file = Path.of(credentialsFile);
            Files.createDirectories(file.getParent());
            restrict(file.getParent(), "rwx------");
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                restrict(lockFile, "rw-------");
                if (!Files.exists(file)) {
                    byte[] secret = new byte[24];
                    new SecureRandom().nextBytes(secret);
                    Path temporary = Files.createTempFile(file.getParent(), ".initial-admin-", ".tmp");
                    try {
                        restrict(temporary, "rw-------");
                        Files.writeString(temporary, "username=admin\npassword=" + HexFormat.of().formatHex(secret) + "\n");
                        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                }
                restrict(file, "rw-------");
                Properties values = new Properties();
                try (var input = Files.newInputStream(file)) { values.load(input); }
                String username = values.getProperty("username", "");
                String password = values.getProperty("password", "");
                if (!username.matches("[A-Za-z0-9_.@-]{3,100}") || password.length() < 12 || password.length() > 512
                        || password.equalsIgnoreCase(username)) {
                    throw new IllegalStateException("持久化的初始管理员凭据无效");
                }
                return new Credentials(username, password);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读写初始管理员凭据文件，请检查持久卷权限", exception);
        }
    }

    private void restrict(Path path, String mode) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
        }
    }

    private int nextTokenVersion(Integer value) {
        return value == null ? 1 : value + 1;
    }

    private static final class Credentials {
        private final String username;
        private final String password;

        private Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }
}
