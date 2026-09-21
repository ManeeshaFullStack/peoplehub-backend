package com.peoplehub.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peoplehub.support.ApiWebTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exception messages can carry personal data (CLAUDE.md, b0-4): a Postgres unique violation quotes
 * the offending key. Whatever the message says, it must reach neither the client nor the log.
 */
@ApiWebTest
@ExtendWith(OutputCaptureExtension.class)
class ExceptionMessageScrubbingTest {

    private static final String BASE = "/api/v1/api-test";
    private static final String SECRET = "jane.doe@example.com";

    @Autowired private MockMvc mvc;

    @Test
    void aUniqueViolationMessageReachesNeitherTheClientNorTheLog(CapturedOutput output)
            throws Exception {
        String body =
                mvc.perform(get(BASE + "/integrity"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.type").value("urn:peoplehub:problem:internal-error"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .doesNotContain(SECRET)
                .doesNotContain("uq_employee_email")
                .doesNotContain("duplicate key");
        assertThat(output.getAll())
                .doesNotContain(SECRET)
                .doesNotContain("uq_employee_email")
                .doesNotContain("duplicate key")
                .doesNotContain("Key (email)");
    }

    @Test
    void theFailureIsStillDiagnosableFromTheLog(CapturedOutput output) throws Exception {
        mvc.perform(get(BASE + "/integrity"));

        // What was thrown and where, including the cause, without what it said.
        assertThat(output.getAll())
                .contains("Unhandled exception while processing request")
                .contains("org.springframework.dao.DataIntegrityViolationException")
                .contains("Caused by: java.sql.SQLException")
                .contains("SampleApiController.integrity");
    }
}
