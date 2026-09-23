package com.admin.service;

import com.admin.entity.VpsHost;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/** Server-side SSH transport; no credential is ever returned to a browser. */
public interface VpsSshService {

    HealthCheckResult check(VpsHost host, String password);

    TerminalConnection openTerminal(VpsHost host, String password) throws Exception;

    CommandResult runTemplate(VpsHost host, String password, String template, Consumer<String> outputConsumer) throws Exception;

    class HealthCheckResult {
        private final boolean online;
        private final String message;
        private final long latencyMs;
        private final String fingerprint;
        private final boolean fingerprintChanged;

        public HealthCheckResult(boolean online, String message, long latencyMs, String fingerprint, boolean fingerprintChanged) {
            this.online = online;
            this.message = message;
            this.latencyMs = latencyMs;
            this.fingerprint = fingerprint;
            this.fingerprintChanged = fingerprintChanged;
        }

        public boolean isOnline() { return online; }
        public String getMessage() { return message; }
        public long getLatencyMs() { return latencyMs; }
        public String getFingerprint() { return fingerprint; }
        public boolean isFingerprintChanged() { return fingerprintChanged; }
    }

    class CommandResult {
        private final boolean succeeded;
        private final String message;
        private final String fingerprint;

        public CommandResult(boolean succeeded, String message, String fingerprint) {
            this.succeeded = succeeded;
            this.message = message;
            this.fingerprint = fingerprint;
        }

        public boolean isSucceeded() { return succeeded; }
        public String getMessage() { return message; }
        public String getFingerprint() { return fingerprint; }
    }

    class HostFingerprintChangedException extends IOException {
        private final String presentedFingerprint;

        public HostFingerprintChangedException(String presentedFingerprint) {
            super("SSH 主机指纹已变化，连接已被保护性拒绝");
            this.presentedFingerprint = presentedFingerprint;
        }

        public String getPresentedFingerprint() {
            return presentedFingerprint;
        }
    }

    interface TerminalConnection extends AutoCloseable {
        InputStream getOutput();
        OutputStream getInput();
        String getFingerprint();
        void resize(int columns, int rows) throws IOException;

        @Override
        void close() throws IOException;
    }
}
