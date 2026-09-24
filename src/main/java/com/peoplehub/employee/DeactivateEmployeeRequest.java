package com.peoplehub.employee;

import java.time.LocalDate;

/**
 * Optional body of {@code POST /admin/employees/{id}/deactivate} (b2-6, B2-6/11). {@code exitDate}
 * (ISO {@code yyyy-MM-dd}) is stored only when given, and may not be after today in the
 * organization's timezone; the server never fills it in on its own.
 */
public record DeactivateEmployeeRequest(LocalDate exitDate) {}
