// What the export does: vowelCount is an ordinary static method, under a JDK
// (surefire) and on rontolisp's interpreter (the plugin's test goal). The component
// is checked by examples/examples.yaml and build.sh.

package com.example.vowels;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CounterTest {

    @Test
    void countsTheVowelsTheRustDescriberAsksFor() {
        assertThat(Counter.vowelCount("hello world")).isEqualTo(3);
        assertThat(Counter.vowelCount("a")).isEqualTo(1);
        assertThat(Counter.vowelCount("AEIOU, rhythm")).isEqualTo(5);
    }

}
