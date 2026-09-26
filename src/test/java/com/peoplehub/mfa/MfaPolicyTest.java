package com.peoplehub.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Who each organization MFA policy covers (b2-7, B2-7/1; MFA/4; Spec 8.3). */
class MfaPolicyTest {

    @ParameterizedTest(name = "{0}, {1}, selected={2} -> {3}")
    @CsvSource({
        // DISABLED and OPTIONAL never require MFA, whatever the role or selection.
        "DISABLED, EMPLOYEE, false, false",
        "DISABLED, ADMIN, true, false",
        "DISABLED, SUPER_ADMIN, true, false",
        "OPTIONAL, EMPLOYEE, true, false",
        "OPTIONAL, ADMIN, false, false",
        "OPTIONAL, SUPER_ADMIN, true, false",
        // Admins and Super Admins; an Employee is not covered, selected or not.
        "REQUIRED_FOR_ADMINS, EMPLOYEE, false, false",
        "REQUIRED_FOR_ADMINS, EMPLOYEE, true, false",
        "REQUIRED_FOR_ADMINS, ADMIN, false, true",
        "REQUIRED_FOR_ADMINS, SUPER_ADMIN, false, true",
        // Only the selection counts, never the role.
        "REQUIRED_FOR_SELECTED_USERS, EMPLOYEE, true, true",
        "REQUIRED_FOR_SELECTED_USERS, EMPLOYEE, false, false",
        "REQUIRED_FOR_SELECTED_USERS, ADMIN, false, false",
        "REQUIRED_FOR_SELECTED_USERS, SUPER_ADMIN, false, false",
        "REQUIRED_FOR_SELECTED_USERS, SUPER_ADMIN, true, true",
        // Everyone.
        "REQUIRED_FOR_ALL, EMPLOYEE, false, true",
        "REQUIRED_FOR_ALL, ADMIN, false, true",
        "REQUIRED_FOR_ALL, SUPER_ADMIN, false, true"
    })
    void coverage(MfaPolicy policy, String role, boolean selected, boolean covered) {
        assertThat(policy.covers(role, selected)).isEqualTo(covered);
    }

    @Test
    void onlyDisabledHidesEnrollment() {
        assertThat(MfaPolicy.DISABLED.offersEnrollment()).isFalse();
        assertThat(MfaPolicy.OPTIONAL.offersEnrollment()).isTrue();
        assertThat(MfaPolicy.REQUIRED_FOR_ADMINS.offersEnrollment()).isTrue();
        assertThat(MfaPolicy.REQUIRED_FOR_SELECTED_USERS.offersEnrollment()).isTrue();
        assertThat(MfaPolicy.REQUIRED_FOR_ALL.offersEnrollment()).isTrue();
    }

    @Test
    void theJavaValuesMatchTheDatabaseCheck() {
        // ck_organization_mfa_policy (V19) allows exactly these five values.
        assertThat(MfaPolicy.values())
                .extracting(Enum::name)
                .containsExactly(
                        "DISABLED",
                        "OPTIONAL",
                        "REQUIRED_FOR_ADMINS",
                        "REQUIRED_FOR_SELECTED_USERS",
                        "REQUIRED_FOR_ALL");
    }
}
