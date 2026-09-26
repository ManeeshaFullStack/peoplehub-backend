package com.peoplehub.mfa;

import com.peoplehub.security.principal.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me/mfa} (b2-7, B2-7/4, B2-7/6, B2-7/27; Spec 8.3, 13.0): the signed-in employee's
 * own MFA enrollment and reminder. Needs an access token; takes no id from the request. Answers
 * that carry a secret or recovery codes are sent with {@code Cache-Control: no-store} (B2-7/8).
 */
@RestController
@RequestMapping("/me/mfa")
public class MfaController {

    private final MfaEnrollmentService enrollmentService;
    private final MfaReminders reminders;

    public MfaController(MfaEnrollmentService enrollmentService, MfaReminders reminders) {
        this.enrollmentService = enrollmentService;
        this.reminders = reminders;
    }

    /** {@code POST /api/v1/me/mfa/enroll}: a new pending secret for the caller, shown once. */
    @PostMapping("/enroll")
    public ResponseEntity<MfaEnrollmentResponse> enroll(
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(enrollmentService.enroll(principal));
    }

    /** {@code POST /api/v1/me/mfa/confirm}: enables MFA; returns the recovery codes, once. */
    @PostMapping("/confirm")
    public ResponseEntity<MfaRecoveryCodesResponse> confirm(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody ConfirmMfaRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(enrollmentService.confirm(principal, body.code(), clientAddress(request)));
    }

    /** {@code POST /api/v1/me/mfa/reminder/dismiss}: hides the MFA reminder for an interval. */
    @PostMapping("/reminder/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissReminder(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        reminders.dismiss(principal);
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
