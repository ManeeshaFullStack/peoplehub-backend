package com.peoplehub.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /me/password} (b2-5, B2-5/P9; Spec 8.2): the current password, and the new one twice.
 * There is no employee id: the account is always the signed-in caller's.
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 1024) String currentPassword,
        @NotBlank @Size(max = 1024) String newPassword,
        @NotBlank @Size(max = 1024) String confirmPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[...]";
    }
}
