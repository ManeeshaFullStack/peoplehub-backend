package com.peoplehub.common.api.testsupport;

import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.common.api.paging.PageParams;
import com.peoplehub.common.api.paging.PageQuery;
import com.peoplehub.common.api.paging.PageResponse;
import com.peoplehub.common.api.paging.SortOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that exercise the API standards (prefix, pagination, errors, validation,
 * correlation id, OpenAPI). Active only under the {@code api-test} profile, so it never appears in
 * normal runs, {@code spring-boot:test-run} or the published OpenAPI document.
 */
@Profile("api-test")
@RestController
@RequestMapping("/api-test")
public class SampleApiController {

    public record Item(String name, Instant joinedAt) {}

    public record Address(@NotBlank String city) {}

    public record CreateItem(
            @NotBlank String name,
            @Email String email,
            @Min(1) Integer qty,
            @Valid Address address) {}

    public record QueryEcho(int page, int size, List<String> sort) {}

    public record Stamp(Instant at) {}

    private static final List<Item> ITEMS =
            IntStream.rangeClosed(1, 45)
                    .mapToObj(
                            i ->
                                    new Item(
                                            "item-%02d".formatted(i),
                                            Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)))
                    .toList();

    @GetMapping("/items")
    public PageResponse<Item> list(
            @PageParams(
                            defaultSize = 10,
                            defaultSort = "name,asc",
                            sortable = {"name", "joinedAt"})
                    PageQuery query) {
        List<Item> sorted = new ArrayList<>(ITEMS);
        sorted.sort(comparatorFor(query.sort()));
        int from = (int) Math.min((long) query.page() * query.size(), sorted.size());
        int to = Math.min(from + query.size(), sorted.size());
        return PageResponse.from(
                new PageImpl<>(sorted.subList(from, to), query.toPageable(), sorted.size()));
    }

    @GetMapping("/query-echo")
    public QueryEcho echo(
            @PageParams(
                            defaultSize = 25,
                            defaultSort = "joinedAt,desc",
                            sortable = {"name", "joinedAt", "status"})
                    PageQuery query) {
        return toEcho(query);
    }

    @GetMapping("/unsortable")
    public QueryEcho unsortable(@PageParams PageQuery query) {
        return toEcho(query);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Item create(@Valid @RequestBody CreateItem body) {
        return new Item(body.name(), Instant.parse("2026-03-08T06:59:59.999Z"));
    }

    @GetMapping("/limit")
    public String limit(@RequestParam @Min(1) @Max(5) int limit) {
        return "ok";
    }

    @GetMapping("/typed/{id}")
    public String typed(@PathVariable long id) {
        return "ok";
    }

    @GetMapping("/problem")
    public void problem() {
        throw new ApiProblemException(ProblemType.NOT_FOUND, "Sample thing was not found.");
    }

    @GetMapping("/boom")
    public void boom() {
        throw new IllegalStateException("secret internal detail for jane.doe@example.com");
    }

    /**
     * Fails the way an unhandled unique violation does: the message quotes the offending value.
     * Used to prove that value reaches neither the client, the logs nor Sentry.
     */
    @GetMapping("/integrity")
    public void integrity() {
        throw new DataIntegrityViolationException(
                "could not execute statement; ERROR: duplicate key value violates unique constraint"
                        + " \"uq_employee_email\"\n  Detail: Key (email)=(jane.doe@example.com)"
                        + " already exists.",
                new SQLException("Key (email)=(jane.doe@example.com) already exists.", "23505"));
    }

    /** Takes a moment, so a test can close the application while a request is in flight. */
    @GetMapping("/slow")
    public String slow(@RequestParam(defaultValue = "1500") @Min(0) @Max(10_000) long millis)
            throws InterruptedException {
        Thread.sleep(millis);
        return "finished";
    }

    @GetMapping("/instant")
    public Stamp instant() {
        return new Stamp(Instant.parse("2026-03-08T06:59:59.999Z"));
    }

    private static QueryEcho toEcho(PageQuery query) {
        return new QueryEcho(
                query.page(),
                query.size(),
                query.sort().stream().map(o -> o.property() + ":" + o.direction()).toList());
    }

    private static Comparator<Item> comparatorFor(List<SortOrder> orders) {
        Comparator<Item> comparator = (a, b) -> 0;
        for (SortOrder order : orders) {
            Comparator<Item> next =
                    "joinedAt".equals(order.property())
                            ? Comparator.comparing(Item::joinedAt)
                            : Comparator.comparing(Item::name);
            comparator =
                    comparator.thenComparing(
                            order.direction() == Sort.Direction.DESC ? next.reversed() : next);
        }
        return comparator;
    }
}
