package com.peoplehub.common.observability;

import com.peoplehub.common.api.correlation.CorrelationId;
import com.peoplehub.common.logging.ActorId;
import com.peoplehub.common.logging.OrganizationId;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Strips personal data from every event before it leaves the process (Spec 14.2, 15). Sentry is a
 * third party, so what may leave is decided here. Tags and contexts are a true <em>allowlist</em>:
 * only the listed keys survive, so anything an integration adds is dropped. Every other field that
 * can carry data is cleared by name (see below). A new top-level field added by a future SDK
 * version would <em>not</em> be cleared automatically, so re-check this class when bumping {@code
 * sentry.version}.
 *
 * <p>An event keeps: the exception type, module, stack frames and mechanism; the log message
 * <em>template</em> (never the formatted text or its arguments, which are where values end up); the
 * correlation id, actor id and organization id as tags, which tie it to the logs; level, timestamp,
 * release, environment and SDK information. It loses: exception messages (a Postgres unique
 * violation quotes the offending key), request data (URL, query, headers, cookies, body), user,
 * breadcrumbs, extras, server name, transaction name and every context except runtime, OS and
 * Spring.
 *
 * <p>Exceptions are captured from the log record written for them, so this and {@link
 * com.peoplehub.common.logging.MessageFreeStackTracePrinter} enforce the same rule for the two
 * places a stack trace can go.
 */
@Component
public class SentryEventScrubber implements SentryOptions.BeforeSendCallback {

    // organizationId: an internal id, allowed for support correlation (Spec 15.1; b2-3, B2-3/18).
    static final Set<String> ALLOWED_TAGS =
            Set.of(CorrelationId.MDC_KEY, ActorId.MDC_KEY, OrganizationId.MDC_KEY);
    static final Set<String> ALLOWED_CONTEXTS = Set.of("runtime", "os", "spring");

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        scrubExceptions(event);
        scrubMessage(event);

        event.setThrowable(null);
        event.setRequest(null);
        event.setUser(null);
        event.setBreadcrumbs(null);
        event.setExtras(null);
        event.setServerName(null);
        event.setTransaction(null);
        event.setModules(null);

        retainOnlyAllowedTags(event);
        retainOnlyAllowedContexts(event);
        return event;
    }

    private static void scrubExceptions(SentryEvent event) {
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            exceptions.forEach(exception -> exception.setValue(null));
        }
    }

    private static void scrubMessage(SentryEvent event) {
        Message message = event.getMessage();
        if (message != null) {
            message.setFormatted(null);
            message.setParams(null);
        }
    }

    private static void retainOnlyAllowedTags(SentryEvent event) {
        if (event.getTags() == null) {
            return;
        }
        new ArrayList<>(event.getTags().keySet())
                .stream().filter(key -> !ALLOWED_TAGS.contains(key)).forEach(event::removeTag);
    }

    private static void retainOnlyAllowedContexts(SentryEvent event) {
        if (event.getContexts() == null) {
            return;
        }
        List<String> keys = Collections.list(event.getContexts().keys());
        keys.stream()
                .filter(key -> !ALLOWED_CONTEXTS.contains(key))
                .forEach(event.getContexts()::remove);
    }
}
