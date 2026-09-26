package com.peoplehub.auth;

import com.peoplehub.mfa.MfaChallenges;
import java.util.Optional;

/**
 * What the password step of a login produced (b2-3; b2-7, B2-7/9): nothing (the one generic
 * failure), a session, or an MFA step that must be completed before a session exists.
 */
sealed interface LoginResult {

    /** The session, when the login opened one directly. */
    default Optional<SessionTokens> session() {
        return Optional.empty();
    }

    /** "We couldn't sign you in with those details", whatever the reason. */
    record Failed() implements LoginResult {}

    /** The password was enough: a new session. */
    record Session(SessionTokens tokens) implements LoginResult {

        @Override
        public Optional<SessionTokens> session() {
            return Optional.of(tokens);
        }
    }

    /**
     * The password was right, but the person must now prove their second factor ({@code CHALLENGE})
     * or enroll it ({@code ENROLL}); the raw challenge token is shown once.
     */
    record MfaStep(MfaChallenges.Purpose purpose, String challengeToken) implements LoginResult {

        @Override
        public String toString() {
            return "MfaStep[purpose=" + purpose + "]";
        }
    }
}
