package com.peoplehub.invitation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /admin/employees/invite} and {@code POST /super-admin/admins/invite} (b2-4, Spec
 * 2.1.5, 13.0). There is deliberately no organization and no role field: the organization is the
 * caller's own and the role is fixed by the endpoint (B2-4 decisions).
 *
 * @param employeeCode optional; generated when absent (B2-4/O2)
 * @param joinDate optional
 */
public record InviteRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String employeeCode,
        LocalDate joinDate) {}
