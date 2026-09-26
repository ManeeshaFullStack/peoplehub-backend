package com.peoplehub.notification.inapp.testsupport;

import com.peoplehub.common.api.paging.PageParams;
import com.peoplehub.common.api.paging.PageQuery;
import com.peoplehub.common.api.paging.PageResponse;
import com.peoplehub.common.database.TenantContext;
import com.peoplehub.common.database.TenantTransactions;
import com.peoplehub.notification.inapp.NotificationBroadcastService;
import com.peoplehub.notification.inapp.NotificationMessage;
import com.peoplehub.notification.inapp.NotificationPreferenceService;
import com.peoplehub.notification.inapp.NotificationWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Exercises the b1-3 notification/SSE infrastructure end to end without a real authenticated caller
 * (b1-3 decision: no production notification endpoints yet -- B2/B3 own those, once a real
 * principal exists to scope them to). Active only under the {@code notification-test} profile, the
 * same pattern {@code SampleApiController} (b0-3, {@code api-test}) already established: never in
 * normal runs, {@code spring-boot:test-run}, or the published OpenAPI document.
 *
 * <p>Takes {@code organizationId}/{@code employeeId} as explicit request parameters, which is only
 * safe because this controller never runs outside a test profile -- a production endpoint must
 * never do this (CLAUDE.md Section 5, IDOR). For the same reason it binds that organization as the
 * tenant itself (b2-8 C4, V25), standing in for the authenticated principal a real endpoint takes
 * it from.
 */
@Profile("notification-test")
@RestController
@RequestMapping("/notification-test")
public class NotificationTestController {

    public record CreateNotificationRequest(
            @NotNull UUID organizationId, @NotNull UUID employeeId, @NotBlank String type) {}

    public record NotificationView(long id, String type, boolean read, Instant createdAt) {}

    public record PreferenceView(boolean email, boolean inApp) {}

    public record SetPreferenceRequest(
            @NotNull UUID organizationId,
            @NotNull UUID employeeId,
            @NotBlank String type,
            boolean email,
            boolean inApp) {}

    private final NotificationWriter writer;
    private final NotificationPreferenceService preferences;
    private final NotificationBroadcastService broadcast;
    private final JdbcClient jdbc;
    private final TenantTransactions tenants;

    public NotificationTestController(
            NotificationWriter writer,
            NotificationPreferenceService preferences,
            NotificationBroadcastService broadcast,
            JdbcClient jdbc,
            TenantTransactions tenants) {
        this.tenants = tenants;
        this.writer = writer;
        this.preferences = preferences;
        this.broadcast = broadcast;
        this.jdbc = jdbc;
    }

    @PostMapping("/notifications")
    @ResponseStatus(HttpStatus.CREATED)
    public void create(@Valid @RequestBody CreateNotificationRequest body) {
        tenants.inTransaction(
                body.organizationId(),
                () -> {
                    writer.append(
                            NotificationMessage.builder(
                                            body.organizationId(), body.employeeId(), body.type())
                                    .build());
                    return null;
                });
    }

    @GetMapping("/notifications")
    public PageResponse<NotificationView> list(
            @RequestParam UUID organizationId,
            @RequestParam UUID employeeId,
            @PageParams(defaultSize = 20, defaultSort = "createdAt,desc", sortable = "createdAt")
                    PageQuery query) {
        return tenants.inReadOnlyTransaction(
                organizationId, () -> listInTenant(organizationId, employeeId, query));
    }

    private PageResponse<NotificationView> listInTenant(
            UUID organizationId, UUID employeeId, PageQuery query) {
        long total =
                jdbc.sql(
                                "SELECT count(*) FROM notification"
                                        + " WHERE organization_id = ? AND employee_id = ?")
                        .param(organizationId)
                        .param(employeeId)
                        .query(Long.class)
                        .single();
        String direction =
                query.sort().isEmpty() || query.sort().get(0).direction() == Sort.Direction.DESC
                        ? "DESC"
                        : "ASC";
        List<NotificationView> items =
                jdbc.sql(
                                "SELECT id, type, read, created_at FROM notification"
                                        + " WHERE organization_id = ? AND employee_id = ?"
                                        + " ORDER BY created_at "
                                        + direction
                                        + " LIMIT ? OFFSET ?")
                        .param(organizationId)
                        .param(employeeId)
                        .param(query.size())
                        .param((long) query.page() * query.size())
                        .query(
                                (rs, rowNum) ->
                                        new NotificationView(
                                                rs.getLong("id"),
                                                rs.getString("type"),
                                                rs.getBoolean("read"),
                                                rs.getTimestamp("created_at").toInstant()))
                        .list();
        return PageResponse.from(new PageImpl<>(items, query.toPageable(), total));
    }

    @PatchMapping("/notifications/{id}/read")
    public void markRead(
            @PathVariable long id,
            @RequestParam UUID organizationId,
            @RequestParam UUID employeeId) {
        tenants.inTransaction(
                organizationId,
                () ->
                        jdbc.sql(
                                        "UPDATE notification SET read = true"
                                                + " WHERE id = ? AND organization_id = ? AND employee_id = ?")
                                .param(id)
                                .param(organizationId)
                                .param(employeeId)
                                .update());
    }

    @GetMapping("/preferences")
    public PreferenceView getPreference(
            @RequestParam UUID organizationId,
            @RequestParam UUID employeeId,
            @RequestParam String type) {
        NotificationPreferenceService.Preference preference;
        try (TenantContext.Scope scope = TenantContext.open(organizationId)) {
            preference = preferences.get(organizationId, employeeId, type);
        }
        return new PreferenceView(preference.email(), preference.inApp());
    }

    @PutMapping("/preferences")
    public void setPreference(@Valid @RequestBody SetPreferenceRequest body) {
        try (TenantContext.Scope scope = TenantContext.open(body.organizationId())) {
            preferences.set(
                    body.organizationId(),
                    body.employeeId(),
                    body.type(),
                    body.email(),
                    body.inApp());
        }
    }

    @GetMapping("/stream")
    public SseEmitter stream(@RequestParam UUID organizationId, @RequestParam UUID employeeId) {
        return broadcast.subscribe(organizationId, employeeId);
    }
}
