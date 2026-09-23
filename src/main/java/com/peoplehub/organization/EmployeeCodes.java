package com.peoplehub.organization;

import java.security.SecureRandom;

/**
 * Generates the founder's {@code employee.employee_code} at registration (b2-2; B2-2 final
 * decisions, "founder employee_code uses SecureRandom system-generated code"). {@code
 * employee_code} is admin/import-supplied and immutable by design (V9's own comment: "never
 * database-generated" describes the normal, later admin/import flow); registration is the one
 * exception, since no admin exists yet to assign one for the very first employee of a brand-new
 * organization. This generates a real, unique-looking system value rather than a fixed literal
 * every organization's founder would otherwise share.
 *
 * <p>Collision is not handled here: {@code employee}'s own {@code uq_employee_organization_code}
 * unique constraint (scoped per organization) is the backstop, and is not realistically expected to
 * fire for a brand-new organization's first row.
 */
public final class EmployeeCodes {

    // No 0/O/1/I/L: characters that are easy to misread or mistype if this code is ever read aloud
    // or copied by hand.
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int RANDOM_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EmployeeCodes() {}

    public static String generateSystemCode() {
        StringBuilder code = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return "EMP-" + code;
    }
}
