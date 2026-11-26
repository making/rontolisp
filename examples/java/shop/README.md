# An order book: `shop/`

It runs identically on the interpreter, the JVM, WASM and `--component`.

`shop/` is an order book of six packages, one class to a file. Running `shop/Main.java`
reads the rest from the source tree, `examples/java/`: `shop.catalog.Item` is
`shop/catalog/Item.java`.

| Package | Shows |
| --- | --- |
| [`shop`](Main.java) | The entry point: single-type and static imports, an order refused by the program's own exception, one thread per order joined before the report, `printf` |
| [`shop.util`](util) | `Money`, a static method imported by name elsewhere |
| [`shop.catalog`](catalog) | An enum; an abstract class with a `protected` field and two subclasses, `ArrayList` |
| [`shop.order`](order) | A record whose compact constructor validates; a sealed interface of two nested records; `Optional` |
| [`shop.pricing`](pricing) | A functional interface with a `default` method, lambdas and method references, a `switch` over the sealed interface with record patterns and a guard, package-private helpers |
| [`shop.report`](report) | Totals the threads share with no lock: `CopyOnWriteArrayList`, `ConcurrentHashMap.merge`, `AtomicLong`; a `Comparator` chain |

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/java/shop/Main.java                                # interpreter
java -jar $JAR examples/java/shop/Main.java -o Main.class && java Main     # JVM
java -jar $JAR examples/java/shop/Main.java -o shop.wasm && wasmtime run shop.wasm
java -jar $JAR examples/java/shop/Main.java -o shop-c.wasm --component && wasmtime run shop-c.wasm
```

A JDK 25 runs it as it stands (`java examples/java/shop/Main.java`), printing the same
output.
