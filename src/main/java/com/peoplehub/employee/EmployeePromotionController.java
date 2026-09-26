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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/super-admin/employees/{id}/promote-admin} (b2-7, B2-7/17; Spec 13.0): Super
 * Admin, step-up. HTTP only; the rules live in {@link EmployeePromotionService}. The organization
 * is always the caller's own; the path id is looked up inside it.
 */
@RestController
public class EmployeePromotionController {

    private final EmployeePromotionService service;

    EmployeePromotionController(EmployeePromotionService service) {
        this.service = service;
    }

    @PostMapping("/super-admin/employees/{id}/promote-admin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void promoteToAdmin(
            @AuthenticationPrincipal AuthenticatedPrincipal caller,
            @PathVariable UUID id,
            HttpServletRequest request) {
        service.promoteToAdmin(caller, id, clientAddress(request));
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
