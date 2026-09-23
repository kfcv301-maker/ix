package com.admin.service;

/** Issues and consumes one-time, short-lived browser terminal tickets. */
public interface VpsTerminalTicketService {

    IssuedTicket issue(Long userId, boolean administrator, Long vpsId);

    TerminalAccess consume(String rawTicket);

    final class IssuedTicket {
        private final String value;
        private final long expiresAt;

        public IssuedTicket(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        public String getValue() {
            return value;
        }

        public long getExpiresAt() {
            return expiresAt;
        }
    }

    final class TerminalAccess {
        private final Long userId;
        private final boolean administrator;
        private final Long vpsId;
        private final long expiresAt;

        public TerminalAccess(Long userId, boolean administrator, Long vpsId, long expiresAt) {
            this.userId = userId;
            this.administrator = administrator;
            this.vpsId = vpsId;
            this.expiresAt = expiresAt;
        }

        public Long getUserId() {
            return userId;
        }

        public boolean isAdministrator() {
            return administrator;
        }

        public Long getVpsId() {
            return vpsId;
        }

        public long getExpiresAt() {
            return expiresAt;
        }
    }
}
