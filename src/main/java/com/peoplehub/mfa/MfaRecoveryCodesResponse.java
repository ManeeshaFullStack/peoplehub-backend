package com.peoplehub.mfa;

import java.util.List;

/**
 * Ten single-use recovery codes (b2-7, B2-7/8), returned once when enrollment is confirmed and sent
 * with {@code Cache-Control: no-store}. Only their hashes are stored.
 */
public record MfaRecoveryCodesResponse(List<String> recoveryCodes) {

    /** Never prints the codes. */
    @Override
    public String toString() {
        return "MfaRecoveryCodesResponse[...]";
    }
}
