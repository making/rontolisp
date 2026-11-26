// The exports as methods: `rontolisp test WorkerTest.java` runs this on the
// interpreter (or compiled with -o, to a module or a class); javac and JUnit
// run it under a JDK.

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTest {

    @Test
    void addsFibsAndGreets() {
        assertThat(Worker.add(2, 3)).isEqualTo(5);
        assertThat(Worker.fib(20)).isEqualTo(6765);
        assertThat(Worker.greet("Workers")).startsWith("Hello, Workers, from Java");
    }
}
