package com.peoplehub.notification.email;

/**
 * A {@code type} has no matching classpath template, or the template needs a token the payload does
 * not supply (b1-2). Treated by {@link EmailOutboxProcessor} as a permanent failure ({@link
 * EmailErrorCode#TEMPLATE_ERROR}) with no retry: a template mismatch is a data/deployment problem
 * that will not resolve itself by trying again.
 */
public class TemplateRenderException extends RuntimeException {

    public TemplateRenderException(String message) {
        super(message);
    }
}
