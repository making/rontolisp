# Logging

A program of the [subset](index.md) logs through `java.util.logging`, or through SLF4J 2
or Commons Logging bound to it. Where the program runs decides where the records go, and the program is not
changed:

| The build | The records go to |
| --- | --- |
| the interpreter, the JVM, a Preview 1 module, a component, a native executable | the standard error, through the root logger's `ConsoleHandler`, as a JDK's default configuration has it |
| a component built with `--wasi-logging` | the host's `wasi:logging/logging` |
| a `--no-wasi` module built with `--wasi-logging`, a Cloudflare Worker | the console method of the record's level, through the glue `--emit-js-glue` writes |

[`examples/java/logging/`](https://github.com/making/rontolisp/tree/develop/examples/java/logging)
is a longer program: a handler and a formatter of its own, SLF4J through `slf4j-jdk14`,
Commons Logging through SLF4J, and the same records in the host's log when it is built with `--wasi-logging`, with its output
on the four targets and on a JDK.

## java.util.logging

The loggers, levels, handlers, formatters and records are a JDK's, with no configuration
file: the root logger at `INFO` with its handler, and a program that wants another level, a
formatter or a handler of its own sets it as it would under a JDK
([The JDK classes](classes.md), [What differs](deviations.md#logging)).

```java
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class Orders {
    static final Logger LOG = Logger.getLogger("shop.orders");

    public static void main(String[] args) {
        Handler handler = new Handler() {
            public void publish(LogRecord r) {
                System.out.println(r.getLevel() + " " + r.getLoggerName() + " " + r.getSourceClassName() + "."
                        + r.getSourceMethodName() + ": " + new Formatter() {
                            public String format(LogRecord record) {
                                return formatMessage(record);
                            }
                        }.format(r));
            }

            public void flush() {
            }

            public void close() {
            }
        };
        LOG.setUseParentHandlers(false);
        LOG.addHandler(handler);
        LOG.info("placed");
        LOG.log(Level.WARNING, "{0} left of {1}", new Object[] {2, "tea"});
        LOG.fine("below the logger's level");
    }
}
```

```
INFO shop.orders Orders.main: placed
WARNING shop.orders Orders.main: 2 left of tea
```

## SLF4J

SLF4J 2 bound to `java.util.logging` by `slf4j-jdk14` is compiled from its published
sources jars as a dependency of the program (`--java-source-dep`, or a dependency of a
[Maven project](index.md#a-maven-project)); its provider is found by `ServiceLoader`, as
under a JDK. Each record is a `java.util.logging` record, so it goes where the build sends
the others, and it names the class and the method that called SLF4J, as the binding names
them under a JDK.

```console
$ rontolisp App.java --java-source-dep org.slf4j:slf4j-jdk14:2.0.20
$ rontolisp App.java --java-source-dep org.slf4j:slf4j-jdk14:2.0.20 -o app.wasm --component
```

## Commons Logging

Commons Logging 1.3 is compiled from its sources jar as SLF4J is
(`--java-source-dep commons-logging:commons-logging:1.3.6`). It picks its implementation as
under a JDK: with SLF4J beside it, it logs through SLF4J; without it, through its own
`Jdk14Logger`, a `java.util.logging` logger. Each record names the method that called
Commons Logging, as a JDK's stack names it.

Commons Logging finds `Jdk14Logger` by a class name it holds, which a compiled program
finds only where reachability metadata declares it
([Reflection from reachability metadata](index.md#reflection-from-reachability-metadata)):
the program's own, or the shared repository's with `--java-metadata-repository`. Without
it, `LogFactory.getLog` throws `LogConfigurationException: No suitable Log implementation`.

```json
{ "reflection": [ { "type": "org.apache.commons.logging.impl.Jdk14Logger",
  "methods": [ { "name": "<init>", "parameterTypes": [ "java.lang.String" ] } ] } ] }
```

```console
$ rontolisp App.java --java-source-dep commons-logging:commons-logging:1.3.6 --java-metadata-repository 1.1.14
```

## The host's log

`--wasi-logging` makes the root logger's handler the host's log, `wasi:logging/logging`
(`0.1.0-draft`), instead of the standard error. Each record is one entry: its level, the
logger's name as the context, and its text as the message -- `formatMessage`, and what was
thrown as `printStackTrace` writes it, or what a formatter given to the handler makes, one
line's end at its end left off. The handler passes `INFO` and above, as the console does, so
the flag changes where the records go and not which.

| `java.util.logging` | `wasi:logging` |
| --- | --- |
| `FINEST`, `FINER` | `trace` |
| `FINE`, `CONFIG` | `debug` |
| `INFO` | `info` |
| `WARNING` | `warn` |
| `SEVERE` | `error` |
| a level above `SEVERE` | `critical` |

**A component** built with the flag imports the interface, and only a host that offers it
can run it: wasmCloud does; `wasmtime run` 51 does not, and refuses to instantiate the
component (`component imports instance wasi:logging/logging@0.1.0-draft, but a matching
implementation was not found in the linker`). A component built without the flag imports
nothing of logging.

```console
$ rontolisp App.java -o app.wasm --component --wasi-logging
```

**A Cloudflare Worker** -- a `--no-wasi` module -- imports it through the canonical ABI,
and the glue `--emit-js-glue` writes answers it: `defaultHost()` hands each record to the
console method of its level, `console.debug` for `trace` and `debug`, `console.info`,
`console.warn`, `console.error` for `error` and `critical`, with the logger's name ahead of
the message (`shop.orders: placed`). It answers at once, so a program may log while its
instance starts.

```console
$ rontolisp Server.java -o src/worker.wasm --no-wasi --wasi-logging --optimize=size --emit-worker-entry
```

The Maven plugin's parameter is `wasiLogging` ([The Maven plugin](../compiling/maven-plugin.md)).
