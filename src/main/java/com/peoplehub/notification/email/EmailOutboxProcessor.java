package com.peoplehub.notification.email;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;

/**
 * Sends what is due in {@code email_outbox} (b1-2, Spec 9.2). Plain, testable core logic -- no
 * Spring scheduling annotation here, the same separation {@code DailyCloseExample} demonstrates:
 * the scheduling wrapper is {@link EmailOutboxProcessorJob}, which just calls {@link #run()} inside
 * {@code JobRunner}.
 *
 * <p><b>Claim before send:</b> a row is claimed with a conditional {@code UPDATE ... WHERE status
 * IN ('PENDING','RETRYING')} before any network call, so the SMTP attempt itself never happens
 * inside a held database transaction or connection. This is defence in depth on top of ShedLock
 * (which already keeps two instances from running this job at the same moment).
 *
 * <p><b>Stale-claim recovery:</b> a row can only be left stuck in {@code SENDING} if the process
 * crashes between claiming it and recording the outcome (ShedLock prevents a merely slow run from
 * causing this). Each run first reclaims any row that has been {@code SENDING} for longer than
 * {@code staleClaimAfter}, so a crash does not strand a row forever.
 *
 * <p><b>At-least-once delivery, not exactly-once:</b> if the process crashes after the SMTP call
 * succeeds but before the row is marked {@code SENT}, the row is reclaimed and sent again. This is
 * a deliberate, documented trade-off of the outbox pattern, not a defect: guaranteeing delivery and
 * guaranteeing no duplicate are not both achievable without a transactional inbox on the receiving
 * side, which is out of scope here.
 */
public class EmailOutboxProcessor {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final EmailSender sender;
    private final EmailTemplateRenderer renderer;
    private final EmailFailureClassifier classifier;
    private final RetryPolicy retryPolicy;
    private final int batchSize;
    private final Duration staleClaimAfter;
    private final EmailSuppressionService suppressionService;

    public EmailOutboxProcessor(
            JdbcClient jdbc,
            Clock clock,
            EmailSender sender,
            EmailTemplateRenderer renderer,
            EmailFailureClassifier classifier,
            RetryPolicy retryPolicy,
            int batchSize,
            Duration staleClaimAfter,
            EmailSuppressionService suppressionService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.sender = sender;
        this.renderer = renderer;
        this.classifier = classifier;
        this.retryPolicy = retryPolicy;
        this.batchSize = batchSize;
        this.staleClaimAfter = staleClaimAfter;
        this.suppressionService = suppressionService;
    }

    /**
     * Reclaims stale claims, then attempts every due row up to the batch size. Returns how many.
     */
    public int run() {
        reclaimStaleClaims();
        int processed = 0;
        for (long id : selectDueIds()) {
            if (Thread.currentThread().isInterrupted()) {
                // A row not yet claimed is untouched and safe: the next run (or this job's own next
                // catch-up) will pick it up. Only an already-claimed row needs the stale-claim
                // recovery above.
                break;
            }
            if (claim(id)) {
                processOne(id);
                processed++;
            }
        }
        return processed;
    }

    private void reclaimStaleClaims() {
        jdbc.sql(
                        "UPDATE email_outbox SET status = 'RETRYING'"
                                + " WHERE status = 'SENDING' AND last_attempt_at < ?")
                .param(Timestamp.from(clock.instant().minus(staleClaimAfter)))
                .update();
    }

    private List<Long> selectDueIds() {
        return jdbc.sql(
                        "SELECT id FROM email_outbox WHERE status IN ('PENDING', 'RETRYING')"
                                + " AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
                                + " ORDER BY id LIMIT ?")
                .param(Timestamp.from(clock.instant()))
                .param(batchSize)
                .query(Long.class)
                .list();
    }

    /**
     * Marks {@code last_attempt_at} at claim time too, so a stuck claim can be told apart later.
     */
    private boolean claim(long id) {
        int updated =
                jdbc.sql(
                                "UPDATE email_outbox SET status = 'SENDING', last_attempt_at = now()"
                                        + " WHERE id = ? AND status IN ('PENDING', 'RETRYING')")
                        .param(id)
                        .update();
        return updated == 1;
    }

    private void processOne(long id) {
        Row row = loadRow(id);
        // b1-4 suppression gate: checked before rendering or sending, so a known-bad address never
        // even gets a template built for it. Surgical addition to b1-2's own flow -- see
        // EmailSuppressionService for what feeds this list.
        if (suppressionService.isSuppressed(row.recipient())) {
            markFailedWithoutAttempt(id, EmailErrorCode.SUPPRESSED);
            return;
        }
        EmailTemplateRenderer.Rendered rendered;
        try {
            rendered = renderer.render(row.type(), row.payloadJson());
        } catch (TemplateRenderException e) {
            // Permanent, no retry, and no send was ever attempted: attempts is left untouched, only
            // real send attempts (success or MailException failure) count towards it.
            markFailedWithoutAttempt(id, EmailErrorCode.TEMPLATE_ERROR);
            return;
        }
        try {
            String providerMessageId =
                    sender.send(row.recipient(), rendered.subject(), rendered.body());
            markSent(id, providerMessageId);
        } catch (EmailConfigurationException e) {
            // Permanent, no retry, no attempt counted: an operator problem, not a per-email one,
            // and
            // SmtpEmailSender throws this before any network call.
            markFailedWithoutAttempt(id, EmailErrorCode.CONFIGURATION_ERROR);
        } catch (MailException e) {
            handleSendFailure(id, row.recipient(), row.attempts(), classifier.classify(e));
        }
    }

    private void handleSendFailure(
            long id,
            String recipient,
            int attemptsBefore,
            EmailFailureClassifier.Classification classification) {
        int attemptsMade = attemptsBefore + 1;
        if (classification.type() == FailureType.PERMANENT
                || !retryPolicy.hasMoreAttempts(attemptsMade)) {
            // b1-4: a permanent, address-specific rejection suppresses the recipient so nothing
            // retries it later either -- but only ADDRESS_REJECTED. A permanent failure for another
            // reason (for example bad credentials, CONFIGURATION_ERROR's cousin
            // AUTHENTICATION_FAILED) says nothing about the address itself, and a transient failure
            // that merely exhausted its retries (classification.type() == TRANSIENT here) must
            // never
            // suppress -- that is a temporary problem, not a bad address.
            if (classification.type() == FailureType.PERMANENT
                    && classification.code() == EmailErrorCode.ADDRESS_REJECTED) {
                suppressionService.suppress(recipient, SuppressionReason.ADDRESS_REJECTED);
            }
            markFailed(id, classification.code());
        } else {
            markRetrying(id, retryPolicy.nextAttemptAt(attemptsMade), classification.code());
        }
    }

    private Row loadRow(long id) {
        return jdbc.sql(
                        "SELECT recipient, type, payload::text AS payload_json, attempts"
                                + " FROM email_outbox WHERE id = ?")
                .param(id)
                .query(
                        (rs, rowNum) ->
                                new Row(
                                        rs.getString("recipient"),
                                        rs.getString("type"),
                                        rs.getString("payload_json"),
                                        rs.getInt("attempts")))
                .single();
    }

    private void markSent(long id, String providerMessageId) {
        jdbc.sql(
                        "UPDATE email_outbox SET status = 'SENT', attempts = attempts + 1,"
                                + " provider_message_id = ?, last_attempt_at = now(),"
                                + " next_attempt_at = NULL, error = NULL WHERE id = ?")
                .param(providerMessageId)
                .param(id)
                .update();
    }

    private void markRetrying(long id, Instant nextAttemptAt, EmailErrorCode code) {
        jdbc.sql(
                        "UPDATE email_outbox SET status = 'RETRYING', attempts = attempts + 1,"
                                + " next_attempt_at = ?, error = ?, last_attempt_at = now()"
                                + " WHERE id = ?")
                .param(Timestamp.from(nextAttemptAt))
                .param(code.name())
                .param(id)
                .update();
    }

    /** For a failure that followed a real send attempt: counts towards {@code attempts}. */
    private void markFailed(long id, EmailErrorCode code) {
        jdbc.sql(
                        "UPDATE email_outbox SET status = 'FAILED', attempts = attempts + 1,"
                                + " error = ?, last_attempt_at = now() WHERE id = ?")
                .param(code.name())
                .param(id)
                .update();
    }

    /** For a failure before any send was attempted (template/configuration): attempts unchanged. */
    private void markFailedWithoutAttempt(long id, EmailErrorCode code) {
        jdbc.sql(
                        "UPDATE email_outbox SET status = 'FAILED', error = ?,"
                                + " last_attempt_at = now() WHERE id = ?")
                .param(code.name())
                .param(id)
                .update();
    }

    private record Row(String recipient, String type, String payloadJson, int attempts) {}
}
