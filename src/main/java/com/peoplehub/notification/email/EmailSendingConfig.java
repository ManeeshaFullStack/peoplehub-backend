package com.peoplehub.notification.email;

import com.peoplehub.common.database.TenantTransactions;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Wires {@link EmailOutboxProcessor} and its collaborators from configuration (b1-2). All
 * properties are environment-overridable and {@code [confirm]}: unconfirmed defaults, not approved
 * values, the same status the rest of the scheduling defaults carry (CLAUDE.md, "B0-5 decisions").
 *
 * <p>{@code peoplehub.email.from-address} ({@code PEOPLEHUB_EMAIL_FROM_ADDRESS}) deliberately has
 * no default value and no fake placeholder sender: {@code #{null}} is a Spring SpEL fallback, not a
 * property value, so an unset variable resolves to Java {@code null}, never an empty string
 * standing in for a real address. The fallback exists only so the application context can still
 * start without it (this bean, and every test that boots the full context, would otherwise fail to
 * wire on a plain {@code ${...}} placeholder with no default at all). {@link SmtpEmailSender} is
 * what actually enforces "required" -- it refuses to send, loudly, if the value is null or blank.
 * See its Javadoc.
 */
@Configuration(proxyBeanMethods = false)
public class EmailSendingConfig {

    @Bean
    public EmailSender emailSender(
            JavaMailSender mailSender,
            @Value("${peoplehub.email.from-address:#{null}}") String fromAddress) {
        return new SmtpEmailSender(mailSender, fromAddress);
    }

    @Bean
    public RetryPolicy retryPolicy(
            Clock clock, @Value("${peoplehub.email.retry.delays:PT1M,PT5M}") String delays) {
        return new RetryPolicy(clock, RetryPolicy.parseDelays(delays));
    }

    @Bean
    public EmailOutboxProcessor emailOutboxProcessor(
            JdbcClient jdbc,
            EmailSender sender,
            EmailTemplateRenderer renderer,
            EmailFailureClassifier classifier,
            RetryPolicy retryPolicy,
            @Value("${peoplehub.email.outbox.batch-size:100}") int batchSize,
            @Value("${peoplehub.email.outbox.stale-claim-after:PT5M}") String staleClaimAfter,
            EmailSuppressionService suppressionService,
            TenantTransactions tenantTransactions) {
        return new EmailOutboxProcessor(
                jdbc,
                sender,
                renderer,
                classifier,
                retryPolicy,
                batchSize,
                Duration.parse(staleClaimAfter),
                suppressionService,
                tenantTransactions);
    }
}
