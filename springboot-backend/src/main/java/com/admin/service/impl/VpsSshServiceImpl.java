package com.admin.service.impl;

import com.admin.entity.VpsHost;
import com.admin.common.utils.VpsSshTargetPolicy;
import com.admin.service.VpsSshService;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final ThreadPoolExecutor STREAM_EXECUTOR = new ThreadPoolExecutor(
            4, 12, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
        Thread thread = new Thread(runnable, "vps-ssh-stream");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.CallerRunsPolicy());

    @jakarta.annotation.Resource
    private VpsSshTargetPolicy vpsSshTargetPolicy;

    @Value("${vps.backend-install.release:1.4.16}")
    private String backendInstallRelease;

    @Override
    public HealthCheckResult check(VpsHost host, String password) {
        long startedAt = System.nanoTime();
        try (SshConnection connection = connect(host, password)) {
            long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return new HealthCheckResult(true, "SSH 认证成功", latency, connection.fingerprint, false);
        } catch (HostFingerprintChangedException exception) {
            return new HealthCheckResult(false, "SSH 主机指纹已变化，连接已被保护性拒绝", 0,
                    exception.getPresentedFingerprint(), true);
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
        }
    }

    @Override
    public int runPoolAgentScript(VpsHost host, String password, String script, int timeoutSeconds) throws Exception {
        if (trimToNull(host.getSshFingerprint()) == null) throw new IllegalArgumentException("SSH 主机指纹缺失");
        String remoteCommand = "root".equals(host.getSshUsername()) ? "bash -s"
                : "if sudo -n true 2>/dev/null; then exec sudo -n bash -s; else exit 44; fi";
        try (SshConnection connection = connect(host, password);
             Session session = connection.client.startSession();
             Session.Command command = session.exec(remoteCommand)) {
            Future<?> output = stream(command.getInputStream(), ignored -> {});
            Future<?> errors = stream(command.getErrorStream(), ignored -> {});
            try (OutputStream stdin = command.getOutputStream()) {
                stdin.write(script.getBytes(StandardCharsets.UTF_8));
            }
            command.join(timeoutSeconds, TimeUnit.SECONDS);
            Integer status = command.getExitStatus();
            if (status == null) return -1;
            output.get(5, TimeUnit.SECONDS);
            errors.get(5, TimeUnit.SECONDS);
            return status;
        }
    }

    private SshConnection connect(VpsHost host, String password) throws Exception {
        if (host == null || isBlank(host.getHost()) || isBlank(host.getSshUsername()) || isBlank(password)) {
            throw new IllegalArgumentException("VPS SSH 配置不完整");
        }

        String targetAddress = vpsSshTargetPolicy.resolveForConnection(host);
        AtomicReference<String> presentedFingerprint = new AtomicReference<>();
        AtomicBoolean acceptedFingerprint = new AtomicBoolean();
        String expectedFingerprint = trimToNull(host.getSshFingerprint());
        SSHClient client = new SSHClient();
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        client.setTimeout(SOCKET_TIMEOUT_MS);
        client.addHostKeyVerifier(new HostKeyVerifier() {
            @Override
            public boolean verify(String hostname, int port, PublicKey key) {
                String actualFingerprint = fingerprint(key);
                presentedFingerprint.set(actualFingerprint);
                boolean accepted = matchesFingerprint(expectedFingerprint, key);
                acceptedFingerprint.set(accepted);
                return accepted;
            }

            @Override
            public List<String> findExistingAlgorithms(String hostname, int port) {
                return Collections.emptyList();
            }
        });

        try {
            client.connect(targetAddress, host.getSshPort() == null ? 22 : host.getSshPort());
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
                    && !acceptedFingerprint.get()) {
                throw new HostFingerprintChangedException(actualFingerprint);
            }
            throw exception;
        }
    }

    @Override public CommandResult installAddressObserver(VpsHost host,String password,String scriptBase64,String expectedNodeDigest) throws Exception {
        String program=observerProgram(scriptBase64,expectedNodeDigest);
        try (SshConnection connection=connect(host,password);Session session=connection.client.startSession();Session.Command command=session.exec("python3 -")) {
            // Fixed Python program over encrypted stdin, never a shell parameter or browser-provided script.
            Future<?> output=stream(command.getInputStream(),ignored->{}),errors=stream(command.getErrorStream(),ignored->{});
            command.getOutputStream().write(program.getBytes(StandardCharsets.UTF_8));command.getOutputStream().close();
            command.join(90,TimeUnit.SECONDS);
            if (command.getExitStatus()==null) { command.close();return new CommandResult(false,"在线启用超时，请检查采集器状态",connection.fingerprint); }
            output.get(5,TimeUnit.SECONDS);errors.get(5,TimeUnit.SECONDS);
            boolean ok=command.getExitStatus()==0;
            return new CommandResult(ok,ok?"上报组件已就绪，转发核心未重启":"上报组件未就绪，请检查节点身份、Python3、配置权限和面板连接",connection.fingerprint);
        }
    }
    static String observerProgram(String scriptBase64,String expectedNodeDigest) {
        if (scriptBase64==null || scriptBase64.length()>180000 || !scriptBase64.matches("[A-Za-z0-9+/=]+")
                || expectedNodeDigest==null || !expectedNodeDigest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("无效的上报组件");
        return "import base64,hashlib,hmac,os,tempfile,runpy,fcntl,stat,sys\n"
                +"lock=os.open('/run/lunaris-address-observer-install.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)\n"
                +"info=os.fstat(lock)\nassert os.geteuid()==0 and info.st_uid==0 and stat.S_ISREG(info.st_mode) and not info.st_mode & 0o077\n"
                +"fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                +"with tempfile.TemporaryDirectory(prefix='lunaris-observer-') as work:\n"
                +" p=work+'/observer.py'\n with open(p,'wb') as f: f.write(base64.b64decode('"+scriptBase64+"',validate=True))\n"
                +" module=runpy.run_path(p)\n module['require_root']()\n _,secret=module['read_identity']('/etc/flux-panel-agent')\n"
                +" if not hmac.compare_digest(hashlib.sha256(secret.encode()).hexdigest(),'"+expectedNodeDigest+"'): sys.exit(42)\n"
                +" result=module['install']('/etc/flux-panel-agent')\n sys.exit(0 if result.get('installed') is True else 43)\n";
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
        if ("backend".equals(template)) {
            String release = backendInstallRelease == null ? "" : backendInstallRelease.trim();
            if (!release.matches("[0-9]+(?:\\.[0-9]+){1,3}")) {
                throw new IllegalStateException("VPS 后端安装版本配置无效");
            }
            // Public integration contract: this URL must remain unchanged.
            // The script may evolve, but the backend source stays pinned below.
            String installerUrl = "https://raw.githubusercontent.com/kfcv301-maker/ix/main/backend_install.sh";
            return "set -eu\n"
                    + "command -v bash >/dev/null 2>&1 || { echo '[后端安装] 缺少 bash。' >&2; exit 1; }\n"
                    + "backend_installer=$(mktemp)\n"
                    + "backend_checksum=$(mktemp)\n"
                    + "trap 'rm -f \"$backend_installer\" \"$backend_checksum\"' EXIT\n"
                    + "curl -fsSL --retry 3 " + installerUrl + " -o \"$backend_installer\"\n"
                    + "curl -fsSL --retry 3 " + installerUrl + ".sha256 -o \"$backend_checksum\"\n"
                    + "expected=$(awk '{print $1}' \"$backend_checksum\"); actual=$(sha256sum \"$backend_installer\" | awk '{print $1}')\n"
                    + "[ \"$expected\" = \"$actual\" ] || { echo '后端安装脚本校验失败' >&2; exit 1; }\n"
                    + "command -v flock >/dev/null 2>&1 || { echo '缺少 flock，请安装 util-linux' >&2; exit 1; }\n"
                    + "REPO_REF=" + release + " flock -n /var/lock/lunaris-backend-install.lock bash \"$backend_installer\" install\n";
        }
        throw new IllegalArgumentException("不支持的部署模板");
    }

    private static String fingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(new Buffer.PlainBuffer().putPublicKey(key).getCompactData());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private static String legacyFingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private static boolean matchesFingerprint(String expected, PublicKey key) {
        // Old pins remain bound to the exact same key during conversion.
        return expected == null || constantTimeEquals(expected, fingerprint(key))
                || constantTimeEquals(expected, legacyFingerprint(key));
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

}
