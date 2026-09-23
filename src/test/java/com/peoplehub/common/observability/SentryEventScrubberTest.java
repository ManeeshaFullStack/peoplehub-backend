package com.peoplehub.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.common.logging.OrganizationId;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackTrace;
import io.sentry.protocol.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SentryEventScrubberTest {

    private static final String SECRET = "jane.doe@example.com";

    private final SentryEventScrubber scrubber = new SentryEventScrubber();

    private SentryEvent eventFullOfPersonalData() {
        SentryEvent event = new SentryEvent(new IllegalStateException(SECRET));
        event.setLevel(SentryLevel.ERROR);
        event.setEnvironment("staging");
        event.setRelease("1.2.3");
        event.setLogger("com.peoplehub.Example");

        SentryException exception = new SentryException();
        exception.setType("IllegalStateException");
        exception.setModule("java.lang");
        exception.setValue("Key (email)=(" + SECRET + ") already exists.");
        exception.setStacktrace(new SentryStackTrace());
        event.setExceptions(new ArrayList<>(List.of(exception)));

        Message message = new Message();
        message.setMessage("Could not save {}");
        message.setFormatted("Could not save " + SECRET);
        message.setParams(List.of(SECRET));
        event.setMessage(message);

        Request request = new Request();
        request.setUrl("https://api.example/approve/tok-123?email=" + SECRET);
        request.setQueryString("email=" + SECRET);
        request.setCookies("session=abc");
        request.setHeaders(Map.of("Authorization", "Bearer secret"));
        request.setData("{\"email\":\"" + SECRET + "\"}");
        event.setRequest(request);

        User user = new User();
        user.setEmail(SECRET);
        user.setIpAddress("203.0.113.9");
        user.setId("42");
        event.setUser(user);

        Breadcrumb crumb = new Breadcrumb("saving " + SECRET);
        event.setBreadcrumbs(new ArrayList<>(List.of(crumb)));

        event.setExtra("email", SECRET);
        event.setServerName("hr-laptop-of-jane");
        event.setTransaction("/api/v1/approvals/tok-123");
        event.setTag(CorrelationId.MDC_KEY, "corr-1");
        event.setTag(ActorId.MDC_KEY, "42");
        event.setTag(OrganizationId.MDC_KEY, "masked-value");
        event.setTag("email", SECRET);
        event.setTag("http.url", "https://api.example/approve/tok-123");
        event.getContexts().put("runtime", "java");
        event.getContexts().put("os", "linux");
        event.getContexts().put("MDC", Map.of("email", SECRET));
        event.getContexts().put("device", "jane's laptop");
        return event;
    }

    @Test
    void removesTheMessageFromEveryException() {
        SentryEvent scrubbed = scrubber.execute(eventFullOfPersonalData(), new Hint());

        assertThat(scrubbed.getExceptions()).hasSize(1);
        SentryException exception = scrubbed.getExceptions().get(0);
        assertThat(exception.getValue()).isNull();
        assertThat(exception.getType()).isEqualTo("IllegalStateException");
        assertThat(exception.getModule()).isEqualTo("java.lang");
        assertThat(exception.getStacktrace()).isNotNull();
    }

    @Test
    void keepsTheLogMessageTemplateButDropsTheFormattedTextAndArguments() {
        SentryEvent scrubbed = scrubber.execute(eventFullOfPersonalData(), new Hint());

        assertThat(scrubbed.getMessage().getMessage()).isEqualTo("Could not save {}");
        assertThat(scrubbed.getMessage().getFormatted()).isNull();
        assertThat(scrubbed.getMessage().getParams()).isNull();
    }

    @Test
    void dropsRequestUserBreadcrumbsExtrasServerAndTransaction() {
        SentryEvent scrubbed = scrubber.execute(eventFullOfPersonalData(), new Hint());

        assertThat(scrubbed.getThrowable()).isNull();
        assertThat(scrubbed.getRequest()).isNull();
        assertThat(scrubbed.getUser()).isNull();
        assertThat(scrubbed.getBreadcrumbs()).isNull();
        assertThat(scrubbed.getExtras()).isNull();
        assertThat(scrubbed.getServerName()).isNull();
        assertThat(scrubbed.getTransaction()).isNull();
    }

    @Test
    void keepsOnlyTheAllowlistedTags() {
        SentryEvent scrubbed = scrubber.execute(eventFullOfPersonalData(), new Hint());

        assertThat(scrubbed.getTags())
                .containsOnlyKeys(CorrelationId.MDC_KEY, ActorId.MDC_KEY, OrganizationId.MDC_KEY)
                .containsEntry(CorrelationId.MDC_KEY, "corr-1")
                .containsEntry(ActorId.MDC_KEY, "42")
                .containsEntry(OrganizationId.MDC_KEY, "masked-value");
    }

    @Test
    void keepsOnlyTheAllowlistedContexts() {
        SentryEvent scrubbed = scrubber.execute(eventFullOfPersonalData(), new Hint());

        assertThat(java.util.Collections.list(scrubbed.getContexts().keys()))
                .containsExactlyInAnyOrder("runtime", "os");
    }

    @Test
    void keepsWhatIsNeededToTriageTheError() {
        SentryEvent scrubbed = scrubber.execute(eventFullOfPersonalData(), new Hint());

        assertThat(scrubbed.getLevel()).isEqualTo(SentryLevel.ERROR);
        assertThat(scrubbed.getEnvironment()).isEqualTo("staging");
        assertThat(scrubbed.getRelease()).isEqualTo("1.2.3");
        assertThat(scrubbed.getLogger()).isEqualTo("com.peoplehub.Example");
    }

    @Test
    void neverReturnsNullSoTheEventIsStillReported() {
        assertThat(scrubber.execute(eventFullOfPersonalData(), new Hint())).isNotNull();
    }

    @Test
    void copesWithAnEventThatHasNothingToScrub() {
        SentryEvent bare = new SentryEvent();

        SentryEvent scrubbed = scrubber.execute(bare, new Hint());

        assertThat(scrubbed).isSameAs(bare);
    }
}
