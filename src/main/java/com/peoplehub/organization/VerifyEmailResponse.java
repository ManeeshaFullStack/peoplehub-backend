package com.peoplehub.organization;

/**
 * {@code verified} is the only outcome signal: a wrong, expired or already-used token all return
 * {@code false} identically (D8's approval-token precedent: "already-decided or expired -> a
 * generic outcome, never a distinguishing error"), so the response never becomes a token-guessing
 * oracle.
 */
public record VerifyEmailResponse(boolean verified) {}
