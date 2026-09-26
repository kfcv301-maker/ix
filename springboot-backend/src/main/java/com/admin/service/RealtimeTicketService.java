package com.admin.service;

/** Short-lived, one-time authorization for browser monitoring WebSockets. */
public interface RealtimeTicketService {

    IssuedTicket issue(Long userId);

    Access consume(String rawTicket);

    void revokeUserTickets(Long userId);

    class IssuedTicket {
        private final String value;
        private final long expiresAt;

        public IssuedTicket(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        public String getValue() { return value; }
        public long getExpiresAt() { return expiresAt; }
    }

    class Access {
        private final Long userId;
        private final long expiresAt;

        public Access(Long userId, long expiresAt) {
            this.userId = userId;
            this.expiresAt = expiresAt;
        }

        public Long getUserId() { return userId; }
        public long getExpiresAt() { return expiresAt; }
    }
}
