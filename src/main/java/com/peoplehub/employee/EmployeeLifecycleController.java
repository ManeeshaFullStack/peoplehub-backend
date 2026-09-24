package com.peoplehub.employee;

import com.peoplehub.security.principal.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/admin/employees/{id}/deactivate} and {@code /reactivate} (b2-6, B2-6/7; Spec
 * 13.0, 13). Authenticated; who may act on whom is decided in {@link EmployeeLifecycleService}.
 * HTTP only. The organization is always the caller's own; the path id is looked up inside it.
 */
@RestController
@RequestMapping("/admin/employees/{id}")
public class EmployeeLifecycleController {

    private final EmployeeLifecycleService service;

    public EmployeeLifecycleController(EmployeeLifecycleService service) {
        this.service = service;
    }

    /** Deactivates the employee; the body, with its optional {@code exitDate}, may be omitted. */
    @PostMapping("/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(
            @AuthenticationPrincipal AuthenticatedPrincipal caller,
            @PathVariable UUID id,
            @RequestBody(required = false) DeactivateEmployeeRequest body,
            HttpServletRequest request) {
        service.deactivate(caller, id, body, clientAddress(request));
    }

    /** Reactivates a deactivated employee; they then sign in again. */
    @PostMapping("/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reactivate(
            @AuthenticationPrincipal AuthenticatedPrincipal caller,
            @PathVariable UUID id,
            HttpServletRequest request) {
        service.reactivate(caller, id, clientAddress(request));
    }

    /** The direct peer's address; forwarded headers are not trusted yet (B2-3/15). */
    private static InetAddress clientAddress(HttpServletRequest request) {
        try {
            String address = request.getRemoteAddr();
            return address == null ? null : InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
