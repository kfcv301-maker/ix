package com.admin.common.constants;

/**
 * Runtime states for a forwarding rule.  These are deliberately separate
 * from {@code BaseEntity.status}: a forward is a live resource, so its status
 * describes the state currently confirmed on its GOST endpoints.
 */
public final class ForwardStatus {

    private ForwardStatus() {
    }

    /** Every endpoint has confirmed that the service is running. */
    public static final int ACTIVE = 1;
    /** Every endpoint has confirmed that the service is paused. */
    public static final int PAUSED = 0;
    /** A configuration operation failed outside the durable sync workflow. */
    public static final int ERROR = -1;
    /** At least one endpoint still has to acknowledge pause or resume. */
    public static final int SYNCING = 2;
    /** The record stays billable until all endpoint cleanup is confirmed. */
    public static final int DELETING = 3;
}
