package com.peoplehub.auth;

import com.peoplehub.common.api.error.ApiFieldError;
import com.peoplehub.common.api.error.ApiProblemException;
import com.peoplehub.common.api.error.ProblemType;
import com.peoplehub.mfa.MfaEnrollmentResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth/mfa/challenge}, {@code /enroll} and {@code /enroll/confirm} (b2-7, B2-7/9;
 * Spec 8.3, 13.0): the MFA step of a sign-in. Public in the security chain (there is no session
 * yet); each call is authenticated by the single-use challenge token from the login answer, and the
 * Origin check of login applies. HTTP and cookies only; the rules live in {@link MfaSignInService}.
 *
 * <p>Answers: a session (cookies and {@link TokenResponse}), a new secret for a required
 * enrollment, a 400 for a wrong code that may be tried again, or one 401 for every challenge that
 * cannot be used (unknown, expired, used, exhausted by wrong codes, or an account that is locked or
 * no longer active): sign in again. Answers carrying a secret, codes or tokens are not cached.
 */
@RestController
@RequestMapping("/auth/mfa")
public class MfaSignInController {

    static final String SIGN_IN_AGAIN =
            "Your sign-in could not be completed. Please sign in again.";
    static final String WRONG_CODE = "is incorrect";
    static final String ONE_CODE = "exactly one of code and recoveryCode is required";

    private final MfaSignInService service;
    private final AuthCookies cookies;
    private final CsrfOriginGuard guard;

    MfaSignInController(MfaSignInService service, AuthCookies cookies, CsrfOriginGuard guard) {
        this.service = service;
        this.cookies = cookies;
        this.guard = guard;
    }

    /** {@code POST /api/v1/auth/mfa/challenge}: a TOTP or recovery code completes the sign-in. */
    @PostMapping("/challenge")
    public ResponseEntity<TokenResponse> challenge(
            @Valid @RequestBody MfaChallengeRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        guard.requireAllowedOrigin(request);
        boolean hasCode = body.code() != null && !body.code().isBlank();
        boolean hasRecoveryCode = body.recoveryCode() != null && !body.recoveryCode().isBlank();
        if (hasCode == hasRecoveryCode) {
            throw validationFailure(
                    List.of(
                            new ApiFieldError("code", ONE_CODE),
                            new ApiFieldError("recoveryCode", ONE_CODE)));
        }
        SessionTokens tokens =
                result(
                        service.challenge(
                                body.challengeToken(),
                                hasCode ? body.code() : null,
                                hasCode ? null : body.recoveryCode(),
                                clientAddress(request)));
        cookies.issue(response, tokens);
        return noStore(AuthController.tokenResponse(tokens));
    }

    /** {@code POST /api/v1/auth/mfa/enroll}: the new secret of a required enrollment, once. */
    @PostMapping("/enroll")
    public ResponseEntity<MfaEnrollmentResponse> enroll(
            @Valid @RequestBody MfaEnrollmentChallengeRequest body, HttpServletRequest request) {
        guard.requireAllowedOrigin(request);
        return noStore(
                result(service.startEnrollment(body.challengeToken(), clientAddress(request))));
    }

    /**
     * {@code POST /api/v1/auth/mfa/enroll/confirm}: enables MFA, opens the session and returns the
     * recovery codes, once.
     */
    @PostMapping("/enroll/confirm")
    public ResponseEntity<MfaEnrolledSessionResponse> confirmEnrollment(
            @Valid @RequestBody MfaEnrollmentConfirmRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        guard.requireAllowedOrigin(request);
        MfaSignInService.EnrolledSession enrolled =
                result(
                        service.confirmEnrollment(
                                body.challengeToken(), body.code(), clientAddress(request)));
        SessionTokens tokens = enrolled.tokens();
        cookies.issue(response, tokens);
        return noStore(
                new MfaEnrolledSessionResponse(
                        tokens.accessToken().value(),
                        "Bearer",
                        tokens.accessToken().expiresInSeconds(),
                        tokens.csrfToken(),
                        enrolled.recoveryCodes()));
    }

    private static <T> T result(MfaSignInService.Outcome<T> outcome) {
        return switch (outcome) {
            case MfaSignInService.Outcome.Completed<T> completed -> completed.value();
            case MfaSignInService.Outcome.WrongCode<T> wrong ->
                    throw validationFailure(List.of(new ApiFieldError(wrong.field(), WRONG_CODE)));
            case MfaSignInService.Outcome.Ended<T> ended ->
                    throw new ApiProblemException(ProblemType.UNAUTHORIZED, SIGN_IN_AGAIN);
        };
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ApiProblemException validationFailure(List<ApiFieldError> errors) {
        return new ApiProblemException(
                ProblemType.VALIDATION_ERROR, "One or more fields are invalid.", errors, Map.of());
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
