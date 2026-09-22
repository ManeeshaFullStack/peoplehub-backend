package com.peoplehub.notification.email;

/**
 * The lifecycle of one {@code email_outbox} row (Spec 9.2; b1-1/b1-2 planning). All five values are
 * defined now because the table's shape (V4) is shared by both branches, but {@link
 * EmailOutboxWriter} only ever produces {@link #PENDING}: the transitions to every other status are
 * written by the b1-2 processor job, which does not exist yet.
 */
public enum EmailOutboxStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    RETRYING
}
