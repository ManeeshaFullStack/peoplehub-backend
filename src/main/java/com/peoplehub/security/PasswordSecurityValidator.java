package com.peoplehub.security;

import java.util.List;

/**
 * Checks a candidate password before it is hashed and stored (Spec 8.2, 15; b2-2, B2-2/2). An
 * interface so the check point exists and is swappable: {@link LocalPasswordSecurityValidator} is
 * the only implementation for now (length, context terms, a small common-password blocklist), with
 * no external dependency. A future implementation (a k-anonymity breached-password API, or an
 * offline compromised-password list) plugs in later without changing any caller.
 */
public interface PasswordSecurityValidator {

    /**
     * @param rawPassword the candidate password; never echoed in a violation message
     * @param contextTerms values the password must not contain or equal (case-insensitive) -- for
     *     example the organization name, the person's name, or their email's local part. A blank or
     *     very short term is ignored (see the implementation) to avoid rejecting an unrelated
     *     password over a coincidental short substring.
     * @return violation messages, safe to show to the client; empty means the password is
     *     acceptable
     */
    List<String> validate(String rawPassword, List<String> contextTerms);
}
