package com.peoplehub.common.api.error;

/**
 * One entry of the {@code fieldErrors} array (Spec 13.2): the request field that is wrong and why.
 * Messages describe the rule that failed and never echo the rejected value.
 */
public record ApiFieldError(String field, String message) {}
