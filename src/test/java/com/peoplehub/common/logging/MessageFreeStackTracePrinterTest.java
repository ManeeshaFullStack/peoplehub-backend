package com.peoplehub.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class MessageFreeStackTracePrinterTest {

    private static final String SECRET = "jane.doe@example.com";

    private final MessageFreeStackTracePrinter printer = new MessageFreeStackTracePrinter();

    @Test
    void neverPrintsTheExceptionMessage() {
        String out = printer.printStackTraceToString(new IllegalStateException(SECRET));

        assertThat(out).doesNotContain(SECRET).doesNotContain("jane").doesNotContain(": ");
    }

    @Test
    void printsTheExceptionClassAndItsFrames() {
        String out = printer.printStackTraceToString(new IllegalStateException(SECRET));

        assertThat(out)
                .startsWith("java.lang.IllegalStateException\n\tat ")
                .contains(MessageFreeStackTracePrinterTest.class.getName());
    }

    @Test
    void printsTheWholeCauseChainWithoutAnyMessage() {
        Throwable root = new IOException("Key (email)=(" + SECRET + ") already exists.");
        Throwable middle = new IllegalArgumentException("also " + SECRET, root);
        Throwable top = new RuntimeException(SECRET, middle);

        String out = printer.printStackTraceToString(top);

        assertThat(out)
                .doesNotContain(SECRET)
                .doesNotContain("Key (email)")
                .contains("java.lang.RuntimeException")
                .contains("Caused by: java.lang.IllegalArgumentException")
                .contains("Caused by: java.io.IOException");
    }

    @Test
    void namesSuppressedExceptionsWithoutTheirMessages() {
        RuntimeException primary = new RuntimeException("primary");
        primary.addSuppressed(new IllegalStateException(SECRET));

        String out = printer.printStackTraceToString(primary);

        assertThat(out).contains("Suppressed: java.lang.IllegalStateException");
        assertThat(out).doesNotContain(SECRET);
    }

    @Test
    void survivesACircularCauseChain() {
        RuntimeException a = new RuntimeException(SECRET);
        RuntimeException b = new RuntimeException(SECRET, a);
        a.initCause(b);

        String out = printer.printStackTraceToString(a);

        assertThat(out).doesNotContain(SECRET);
        assertThat(out.split("Caused by: ")).hasSize(2);
    }

    @Test
    void capsAnUnreasonablyLongCauseChain() {
        Throwable chain = new RuntimeException();
        for (int i = 0; i < 100; i++) {
            chain = new RuntimeException(chain);
        }

        String out = printer.printStackTraceToString(chain);

        assertThat(out.split("Caused by: ").length).isLessThanOrEqualTo(20);
    }

    @Test
    void producesOneJsonSafeStringThatIsNotJsonItself() {
        // The JSON formatter escapes the newlines, so the event is still a single line of output.
        String out = printer.printStackTraceToString(new IllegalStateException(SECRET));

        assertThat(out).contains("\n").doesNotContain("\r");
    }
}
