// What the program does under a JDK: LocalCasing, the provider
// META-INF/services names, answers the interface and the program prints the
// report. The interpreter, the JVM and the composed component are checked by
// examples/examples.yaml and build.sh instead -- rontolisp reads no test file.

package com.example.casing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppTest {

    @Test
    void runsAgainstTheJdkProvider() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            App.main(new String[0]);
        }
        finally {
            System.setOut(original);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8)).isEqualToNormalizingWhitespace("""
                hello world  ->  HELLO WORLD!
                component model  ->  COMPONENT MODEL!
                rust and lisp  ->  RUST AND LISP!
                """);
    }

}
