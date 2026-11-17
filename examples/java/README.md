# Java sample programs

Programs for the **experimental** subset of the Java language: a `.java` file is read as
that subset, as described in the [Java section](../../doc/en/java/index.md). Each runs
identically on the interpreter, the JVM, WASM and `--component`, and each is checked in
[`examples.yaml`](../examples.yaml).

| Program | Shows |
| --- | --- |
| [`Orders.java`](Orders.java) | An order book: an abstract class and two subclasses behind an interface, a functional interface called through lambdas, overloads, a checked quantity raising the program's own exception, `ArrayList` and `HashMap` |

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/java/Orders.java                                  # interpreter
java -jar $JAR examples/java/Orders.java -o Orders.class && java Orders   # JVM
java -jar $JAR examples/java/Orders.java -o orders.wasm && wasmtime run orders.wasm
java -jar $JAR examples/java/Orders.java -o orders-c.wasm --component && wasmtime run orders-c.wasm
```

`Orders.java` is also a program a JDK 25 runs as it stands (`java Orders.java`), printing
the same output.
