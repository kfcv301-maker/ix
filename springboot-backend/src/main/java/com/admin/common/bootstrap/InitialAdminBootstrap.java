package com.admin.common.bootstrap;

import com.admin.common.utils.PasswordHashes;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.List;

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
                user.setPwd(PasswordHashes.encode(credentials.password));
                user.setTokenVersion(nextTokenVersion(user.getTokenVersion()));
                user.setUpdatedTime(System.currentTimeMillis());
                if (userMapper.updateById(user) != 1) {
                    throw new IllegalStateException("无法替换旧默认管理员密码");
                }
            }
        }
    }

    private void createAdministrator(Credentials credentials) {
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
        if (!username.matches("[A-Za-z0-9_.@-]{3,100}")) {
            throw new IllegalStateException("必须设置 PANEL_INITIAL_ADMIN_USERNAME（3-100 位字母、数字或 ._@-）");
        }
        if (password.length() < 12 || LEGACY_PASSWORD.equals(password)) {
            throw new IllegalStateException("必须设置不少于 12 位且非默认值的 PANEL_INITIAL_ADMIN_PASSWORD");
        }
        return new Credentials(username, password);
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
