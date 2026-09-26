package com.admin.common.utils;

import com.admin.entity.VpsHost;
import com.admin.service.VpsHostService;
import com.admin.service.VpsSshService;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bridges an authorized browser WebSocket to one short-lived SSH shell. */
@Component
public class VpsTerminalWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_INPUT_LENGTH = 16 * 1024;
    /** One reader is held for each active terminal, so this must stay bounded. */
    private static final ThreadPoolExecutor OUTPUT_EXECUTOR = new ThreadPoolExecutor(
            0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "vps-terminal-output");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private final Map<String, TerminalState> terminals = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> terminalSessions = new ConcurrentHashMap<>();

    @Resource
    private VpsHostService vpsHostService;

    @Resource
    private VpsSshService vpsSshService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long hostId = asLong(session.getAttributes().get("vpsId"));
        Long userId = asLong(session.getAttributes().get("userId"));
        boolean administrator = Boolean.TRUE.equals(session.getAttributes().get("administrator"));
        VpsHost host = vpsHostService.findOperableHost(userId, administrator, hostId);
        if (host == null) {
            closeWithError(session, "没有连接此 VPS 的权限");
            return;
        }
        if (!vpsHostService.isFingerprintVerified(host)) {
            closeWithError(session, "请先检测并确认 SSH 主机指纹");
            return;
        }
        String password = vpsHostService.decryptSshPassword(host);
        if (password == null || password.trim().isEmpty()) {
            closeWithError(session, "SSH 凭据无法解密");
            return;
        }

        try {
            VpsSshService.TerminalConnection connection = vpsSshService.openTerminal(host, password);
            vpsHostService.recordSshSuccess(host, connection.getFingerprint(), "在线 SSH 已连接");
            TerminalState state = new TerminalState(connection);
            terminals.put(session.getId(), state);
            terminalSessions.put(session.getId(), session);
            send(session, "ready", "SSH 已连接：" + host.getName());
            try {
                OUTPUT_EXECUTOR.execute(() -> copyOutput(session, state));
            } catch (RejectedExecutionException exception) {
                closeState(session);
                try {
                    closeWithError(session, "在线 SSH 会话已达上限，请稍后重试");
                } catch (IOException ignored) {
                    // The client may already have disconnected while the queue was full.
                }
                return;
            }
        } catch (Exception exception) {
            vpsHostService.recordSshFailure(host, safeMessage(exception),
                    exception instanceof VpsSshService.HostFingerprintChangedException);
            closeWithError(session, safeMessage(exception));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        TerminalState state = terminals.get(session.getId());
        if (state == null) return;
        JSONObject payload;
        try {
            payload = JSONObject.parseObject(message.getPayload());
        } catch (Exception exception) {
            return;
        }
        if ("resize".equals(payload.getString("type"))) {
            int columns = payload.getIntValue("cols");
            int rows = payload.getIntValue("rows");
            if (columns < 20 || columns > 500 || rows < 5 || rows > 200) return;
            synchronized (state) {
                state.connection.resize(columns, rows);
            }
            return;
        }
        if (!"input".equals(payload.getString("type"))) return;
        String input = payload.getString("data");
        if (input == null || input.isEmpty() || input.length() > MAX_INPUT_LENGTH) return;
        synchronized (state) {
            OutputStream output = state.connection.getInput();
            output.write(input.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        closeState(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        closeState(session);
    }

    private void copyOutput(WebSocketSession session, TerminalState state) {
        byte[] buffer = new byte[4096];
        try (InputStream input = state.connection.getOutput()) {
            int read;
            while (!state.closed && (read = input.read(buffer)) >= 0) {
                if (read > 0) send(session, "output", new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (Exception exception) {
            if (!state.closed && session.isOpen()) send(session, "error", "SSH 终端已断开：" + safeMessage(exception));
        } finally {
            closeState(session);
        }
    }

    private void closeWithError(WebSocketSession session, String message) throws IOException {
        send(session, "error", message);
        if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
    }

    private void closeState(WebSocketSession session) {
        terminalSessions.remove(session.getId());
        TerminalState state = terminals.remove(session.getId());
        if (state == null) return;
        state.closed = true;
        try {
            state.connection.close();
        } catch (IOException ignored) {
            // The browser connection is already closing.
        }
    }

    /** Password resets and account disables must also terminate live SSH shells. */
    public void closeUserSessions(Long userId) {
        if (userId == null) return;
        for (WebSocketSession session : terminalSessions.values()) {
            if (!userId.equals(asLong(session.getAttributes().get("userId")))) continue;
            closeState(session);
            try {
                if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {
                // It may have closed naturally while this revocation was running.
            }
        }
    }

    /** Revoking or changing a hosted VPS must terminate shells opened before that change. */
    public void closeHostSessions(Long hostId) {
        if (hostId == null) return;
        for (WebSocketSession session : terminalSessions.values()) {
            if (!hostId.equals(asLong(session.getAttributes().get("vpsId")))) continue;
            closeState(session);
            try {
                if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {
                // It may have closed naturally while this revocation was running.
            }
        }
    }

    private void send(WebSocketSession session, String type, String data) {
        if (!session.isOpen()) return;
        JSONObject payload = new JSONObject();
        payload.put("type", type);
        payload.put("data", data);
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(payload.toJSONString()));
            }
        } catch (IOException ignored) {
            // A client disconnect during terminal output is expected.
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) return "SSH 连接失败";
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private static final class TerminalState {
        private final VpsSshService.TerminalConnection connection;
        private volatile boolean closed;

        private TerminalState(VpsSshService.TerminalConnection connection) {
            this.connection = connection;
        }
    }
}
