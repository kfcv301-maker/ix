package com.admin.service.impl;

import com.admin.entity.VpsHost;
import com.admin.service.VpsSshService;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SSHJ-based implementation used by both health checks and the browser
 * terminal. The browser sees only terminal bytes, never a password.
 */
@Service
public class VpsSshServiceImpl implements VpsSshService {

    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int SOCKET_TIMEOUT_MS = 20_000;
    private static final long TASK_TIMEOUT_MINUTES = 30;
    private static final ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "vps-ssh-stream");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public HealthCheckResult check(VpsHost host, String password) {
        long startedAt = System.nanoTime();
        try (SshConnection connection = connect(host, password)) {
            long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return new HealthCheckResult(true, "SSH 认证成功", latency, connection.fingerprint, false);
        } catch (HostFingerprintChangedException exception) {
            return new HealthCheckResult(false, "SSH 主机指纹已变化，连接已被保护性拒绝", 0,
                    exception.presentedFingerprint, true);
        } catch (Exception exception) {
            return new HealthCheckResult(false, friendlyError(exception), 0, null, false);
        }
    }

    @Override
    public TerminalConnection openTerminal(VpsHost host, String password) throws Exception {
        SshConnection connection = connect(host, password);
        try {
            Session session = connection.client.startSession();
            // Empty mode flags let the remote server use normal interactive
            // shell defaults (including input echo), unlike allocateDefaultPTY.
            session.allocatePTY("xterm-256color", 120, 36, 0, 0, Collections.emptyMap());
            Session.Shell shell = session.startShell();
            return new ManagedTerminalConnection(connection, session, shell);
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    @Override
    public CommandResult runTemplate(VpsHost host, String password, String template,
                                     Consumer<String> outputConsumer) throws Exception {
        String command = resolveTemplate(template);
        try (SshConnection connection = connect(host, password);
             Session session = connection.client.startSession();
             Session.Command remoteCommand = session.exec(command)) {
            Future<?> stdoutPump = stream(remoteCommand.getInputStream(), outputConsumer);
            Future<?> stderrPump = stream(remoteCommand.getErrorStream(), outputConsumer);
            remoteCommand.join(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            stdoutPump.get(30, TimeUnit.SECONDS);
            stderrPump.get(30, TimeUnit.SECONDS);
            Integer exitStatus = remoteCommand.getExitStatus();
            boolean succeeded = exitStatus != null && exitStatus == 0;
            return new CommandResult(succeeded,
                    succeeded ? "部署任务执行完成" : "部署任务执行失败，退出码："
                            + (exitStatus == null ? "未知" : exitStatus),
                    connection.fingerprint);
        } catch (HostFingerprintChangedException exception) {
            return new CommandResult(false, "SSH 主机指纹已变化，部署已取消", exception.presentedFingerprint);
        }
    }

    private SshConnection connect(VpsHost host, String password) throws Exception {
        if (host == null || isBlank(host.getHost()) || isBlank(host.getSshUsername()) || isBlank(password)) {
            throw new IllegalArgumentException("VPS SSH 配置不完整");
        }

        AtomicReference<String> presentedFingerprint = new AtomicReference<>();
        String expectedFingerprint = trimToNull(host.getSshFingerprint());
        SSHClient client = new SSHClient();
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        client.setTimeout(SOCKET_TIMEOUT_MS);
        client.addHostKeyVerifier(new HostKeyVerifier() {
            @Override
            public boolean verify(String hostname, int port, PublicKey key) {
                String actualFingerprint = fingerprint(key);
                presentedFingerprint.set(actualFingerprint);
                return expectedFingerprint == null || constantTimeEquals(expectedFingerprint, actualFingerprint);
            }

            @Override
            public List<String> findExistingAlgorithms(String hostname, int port) {
                return Collections.emptyList();
            }
        });

        try {
            client.connect(host.getHost(), host.getSshPort() == null ? 22 : host.getSshPort());
            client.authPassword(host.getSshUsername(), password);
            return new SshConnection(client, presentedFingerprint.get());
        } catch (Exception exception) {
            try {
                client.close();
            } catch (IOException ignored) {
                // The original SSH failure is more useful than a close failure.
            }
            String actualFingerprint = presentedFingerprint.get();
            if (expectedFingerprint != null && actualFingerprint != null
                    && !constantTimeEquals(expectedFingerprint, actualFingerprint)) {
                throw new HostFingerprintChangedException(actualFingerprint);
            }
            throw exception;
        }
    }

    private Future<?> stream(InputStream source, Consumer<String> outputConsumer) {
        return STREAM_EXECUTOR.submit(() -> {
            byte[] buffer = new byte[4096];
            try (InputStream input = source) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        outputConsumer.accept(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException exception) {
                outputConsumer.accept("\n[读取远程输出失败：" + friendlyError(exception) + "]\n");
            }
        });
    }

    private String resolveTemplate(String template) {
        if ("docker".equals(template)) {
            return dockerInstallScript();
        }
        if ("flux_panel".equals(template)) {
            return dockerInstallScript() + "\n"
                    + "echo '[Flux Panel] 开始部署完整面板…'\n"
                    + "curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/panel_install.sh | sh\n";
        }
        throw new IllegalArgumentException("不支持的部署模板");
    }

    private String dockerInstallScript() {
        return "set -eu\n"
                + "if command -v docker >/dev/null 2>&1; then\n"
                + "  echo '[Docker] 已安装：'\n"
                + "  docker --version\n"
                + "else\n"
                + "  echo '[Docker] 正在安装…'\n"
                + "  curl -fsSL https://get.docker.com | sh\n"
                + "  docker --version\n"
                + "fi\n"
                + "docker compose version || true\n";
    }

    private static String fingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String friendlyError(Exception exception) {
        String message = exception.getMessage();
        if (isBlank(message)) return "SSH 连接失败";
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static final class SshConnection implements AutoCloseable {
        private final SSHClient client;
        private final String fingerprint;

        private SshConnection(SSHClient client, String fingerprint) {
            this.client = Objects.requireNonNull(client);
            this.fingerprint = fingerprint;
        }

        @Override
        public void close() throws IOException {
            client.close();
        }
    }

    private static final class ManagedTerminalConnection implements TerminalConnection {
        private final SshConnection connection;
        private final Session session;
        private final Session.Shell shell;

        private ManagedTerminalConnection(SshConnection connection, Session session, Session.Shell shell) {
            this.connection = connection;
            this.session = session;
            this.shell = shell;
        }

        @Override
        public InputStream getOutput() {
            return shell.getInputStream();
        }

        @Override
        public OutputStream getInput() {
            return shell.getOutputStream();
        }

        @Override
        public String getFingerprint() {
            return connection.fingerprint;
        }

        @Override
        public void resize(int columns, int rows) throws IOException {
            try {
                shell.changeWindowDimensions(columns, rows, 0, 0);
            } catch (Exception exception) {
                throw new IOException("更新终端尺寸失败", exception);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                shell.close();
            } finally {
                try {
                    session.close();
                } finally {
                    connection.close();
                }
            }
        }
    }

    private static final class HostFingerprintChangedException extends IOException {
        private final String presentedFingerprint;

        private HostFingerprintChangedException(String presentedFingerprint) {
            super("SSH host fingerprint changed");
            this.presentedFingerprint = presentedFingerprint;
        }
    }
}
