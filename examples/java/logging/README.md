# Logging: `logging/`

An order book that logs. [`Orders.java`](src/main/java/com/example/logging/Orders.java) logs
through `java.util.logging`: a message of a `Supplier`, parameters, a `Throwable`, and a
level of the program's above `SEVERE` ([`App.java`](src/main/java/com/example/logging/App.java)).
[`Stock.java`](src/main/java/com/example/logging/Stock.java) logs through SLF4J 2, which
`slf4j-jdk14` binds to `java.util.logging`: placeholders, the fluent API and an exception.
[`Payments.java`](src/main/java/com/example/logging/Payments.java) logs through Commons
Logging, which finds SLF4J beside it and logs through it.
`App` adds a handler of its own,
[`StdoutHandler`](src/main/java/com/example/logging/StdoutHandler.java) with
[`LineFormatter`](src/main/java/com/example/logging/LineFormatter.java), to the logger
`com.example.logging`, and lowers `Orders`' logger to `FINE`.

SLF4J and Commons Logging are not built in: `slf4j-api`, `slf4j-jdk14` and `commons-logging`
are compiled from their sources jars with the program, and SLF4J's binding is found by
`ServiceLoader`. It is a Maven project
([`examples/java/`](../README.md)):

```bash
cd examples/java/logging
mvn package               # target/logging.wasm, target/logging-component.wasm, target/logging-wasi-logging.wasm
wasmtime run target/logging.wasm
wasmtime run target/logging-component.wasm
mvn exec:exec             # JDK
```

From the repository's root:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
DEP="--java-source-dep org.slf4j:slf4j-jdk14:2.0.20 --java-source-dep commons-logging:commons-logging:1.3.6"
APP=examples/java/logging/src/main/java/com/example/logging/App.java
java -jar $JAR $DEP $APP                                  # interpreter
java -jar $JAR $DEP $APP -o App.class && java App         # JVM
```

Every one of them prints the JDK's lines on the standard output. Each names the class and
the method that logged, those through SLF4J and Commons Logging included; their `debug` is
`FINE`, below the level `Stock`'s and `Payments`' loggers inherit, and is printed nowhere:

```
FINE    Orders.place: order 1: 2 x tea
INFO    Stock.reserve: 1 left of tea
INFO    Orders.place: order 1 placed
FINE    Orders.place: order 2: 2 x coffee
WARNING Stock.reserve: coffee: 2 asked, 1 left
WARNING Orders.place: order 2 refused (java.lang.IllegalStateException: coffee short)
SEVERE  Stock.count: count of milk not read (java.lang.NumberFormatException: For input string: "a dozen")
ALERT   Orders.hold: order 3 held: a new address
INFO    Payments.charge: order 1 charged 6, 6 in all
SEVERE  Payments.charge: order 2 not charged (java.lang.IllegalArgumentException: amount 0)
```

Each record goes on to the root logger's handler, a `ConsoleHandler` at `INFO`: the standard
error has the records of `INFO` and above in `SimpleFormatter`'s two lines, the global
logger's `closed` among them, with the time and, under a JDK, the stack trace of what was
thrown ([What differs](../../../doc/en/java/deviations.md#logging)).

## The host's log

`target/logging-wasi-logging.wasm` is the same program compiled with `--wasi-logging`
(the plugin's `wasiLogging`): the root logger's handler is the host's `wasi:logging`, and
the standard output is unchanged. It runs on a host that offers the interface; `wasmtime
run` does not, and refuses to instantiate it. To see the records, compose it with the
repository's test host of the interface
([`wasi-logging-host/`](../../../src/test/resources/am/ik/rontolisp/testsupport/wasi-logging-host/host.wit)),
which writes each record as `[level] context: message` on the standard error. From the
repository's root, after `mvn package`:

```bash
H=src/test/resources/am/ik/rontolisp/testsupport/wasi-logging-host
wasm-tools parse $H/host.wat -o /tmp/core.wasm
wasm-tools component embed $H/host.wit --world host /tmp/core.wasm -o /tmp/embedded.wasm
wasm-tools component new /tmp/embedded.wasm -o /tmp/host.wasm
wasm-tools compose examples/java/logging/target/logging-wasi-logging.wasm -d /tmp/host.wasm -o /tmp/composed.wasm
wasmtime run /tmp/composed.wasm 2>&1 >/dev/null
```

```
[info] com.example.logging.Stock: 1 left of tea
[info] com.example.logging.Orders: order 1 placed
[warn] com.example.logging.Stock: coffee: 2 asked, 1 left
[warn] com.example.logging.Orders: order 2 refused
java.lang.IllegalStateException: coffee short
[error] com.example.logging.Stock: count of milk not read
java.lang.NumberFormatException: For input string: "a dozen"
[critical] com.example.logging.Orders: order 3 held: a new address
[info] com.example.logging.Payments: order 1 charged 6, 6 in all
[error] com.example.logging.Payments: order 2 not charged
java.lang.IllegalArgumentException: amount 0
[info] global: closed
```

The host is handed the records the console would have printed: `INFO` and above, the level
as `wasi:logging`'s (`ALERT`, above `SEVERE`, is `critical`), the logger's name as the
context, the message and what was thrown as the text
([The host's log](../../../doc/en/java/logging.md#the-hosts-log)).
