package com.peoplehub.notification.inapp;

import java.util.Set;

/**
 * Security-/approval-critical notification types, which cannot be fully disabled (Spec 9.3). Small
 * and deliberately extensible: adding a type here is a one-line change plus a forward-only
 * migration updating {@code ck_notification_preference_critical_always_on} to match -- {@code
 * CriticalNotificationTypesTest} keeps the two in step, the same discipline {@code
 * AuditLogMigrationTest} already applies to {@code ActorId}/{@code CorrelationId}.
 *
 * <p>This initial list (b1-3) is deliberately conservative: the security-critical types that are
 * plausible even before B2's real auth/device workflows exist. It does not attempt to enumerate
 * every eventually-critical type (for example approval-related ones, which need the approval
 * engine, B7) -- that is future work, not a B1-3 business-workflow decision.
 */
public final class CriticalNotificationTypes {

    public static final Set<String> CRITICAL =
            Set.of("PASSWORD_RESET", "NEW_DEVICE_PAIRED", "EMPLOYEE_INVITED");

    private CriticalNotificationTypes() {}
}
