package com.peoplehub.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link EmployeeCodes#generateSystemCode} (b2-2). */
class EmployeeCodesTest {

    @Test
    void startsWithEmpPrefix() {
        assertThat(EmployeeCodes.generateSystemCode()).startsWith("EMP-");
    }

    @Test
    void hasTheExpectedShapeAndFitsTheEmployeeCodeColumn() {
        String code = EmployeeCodes.generateSystemCode();

        assertThat(code).matches("EMP-[A-Z2-9]{8}");
        // employee.employee_code is VARCHAR(64) (V9).
        assertThat(code.length()).isLessThanOrEqualTo(64);
    }

    @Test
    void neverContainsAmbiguousCharacters() {
        String code = EmployeeCodes.generateSystemCode();

        assertThat(code).doesNotContainAnyWhitespaces();
        for (char ambiguous : new char[] {'0', 'O', '1', 'I', 'L'}) {
            assertThat(code).doesNotContain(String.valueOf(ambiguous));
        }
    }

    @Test
    void generatesDifferentCodesEachTime() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(EmployeeCodes.generateSystemCode());
        }

        assertThat(codes).hasSize(200);
    }
}
