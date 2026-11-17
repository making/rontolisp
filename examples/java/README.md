# Java sample programs

A program for the **experimental** subset of the Java language: `.java` files read as that
subset, as described in the [Java section](../../doc/en/java/index.md). It runs identically
on the interpreter, the JVM, WASM and `--component`, and is checked in
[`examples.yaml`](../examples.yaml).

`shop/` is an order book of six packages, one class to a file. Running `shop/Main.java`
reads the rest from the source tree, `examples/java/`: `shop.catalog.Item` is
`shop/catalog/Item.java`.

| Package | Shows |
| --- | --- |
| [`shop`](shop/Main.java) | The entry point: single-type and static imports, an order refused by the program's own exception, one thread per order joined before the report, `printf` |
| [`shop.util`](shop/util) | `Money`, a static method imported by name elsewhere |
| [`shop.catalog`](shop/catalog) | An enum; an abstract class with a `protected` field and two subclasses, `ArrayList` |
| [`shop.order`](shop/order) | A record whose compact constructor validates; a sealed interface of two nested records; `Optional` |
| [`shop.pricing`](shop/pricing) | A functional interface with a `default` method, lambdas and method references, a `switch` over the sealed interface with record patterns and a guard, package-private helpers |
| [`shop.report`](shop/report) | `synchronized` methods the threads share, `HashMap.merge`, a `Comparator` chain |

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/java/shop/Main.java                                # interpreter
java -jar $JAR examples/java/shop/Main.java -o Main.class && java Main     # JVM
java -jar $JAR examples/java/shop/Main.java -o shop.wasm && wasmtime run shop.wasm
java -jar $JAR examples/java/shop/Main.java -o shop-c.wasm --component && wasmtime run shop-c.wasm
```

A JDK 25 runs it as it stands (`java examples/java/shop/Main.java`), printing the same
output.
