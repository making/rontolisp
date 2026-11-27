# wit/sockets -- a Java program on the host's own wasi:sockets

[`Echo.java`](Echo.java) is an echo over loopback TCP: a socket listens, a second one
connects and sends a line, the server answers it in capitals. It calls
`wasi:sockets/types@0.3.0` through [`Sockets.java`](Sockets.java), a Java interface
annotated `@WitImport` ([WIT interfaces and worlds](../../../doc/en/java/wit.md)) and
checked against [`wit/sockets.wit`](wit/sockets.wit), upstream's members trimmed to the
ones the program calls.

What the host answers in pieces is a type of `rontolisp-java-api`:

| WIT | Java | In `Echo.java` |
| --- | --- | --- |
| `result<T, E>` | `WitResult<T, E>`, or the ok arm with a `WitException` thrown | `getLocalAddress`, `bind` |
| `async func` | a method that waits for the answer | `connect` |
| `stream<tcp-socket>` | `WitStream<TcpSocket>` | `listen`'s connections |
| `stream<u8>` the host answers | `InputStream` | `receive` |
| `stream<u8>` the program hands | `Consumer<OutputStream>` | `send` |
| `future<result<_, E>>` | `WitFuture<Void>` | beside `send` and `receive` |

Under a JDK, on the interpreter and on the JVM,
[`LocalSockets.java`](LocalSockets.java), named in
[`META-INF/services/Sockets`](META-INF/services/Sockets), answers over `java.net`, with
`WitStream.of`, `WitFuture.of` and an `OutputStream` of its own. In a component the host
answers. The output is the same:

```bash
API="--java-source-path ../../../rontolisp-java-api/src/main/java"
rontolisp Echo.java $API                                   # interpreter
rontolisp Echo.java $API -o Echo.class && java Echo        # JVM
rontolisp Echo.java $API -o echo.wasm --component
wasmtime run -S inherit-network=y echo.wasm                # wasmtime's wasi:sockets
```
```console
before bind:  none, InvalidState[]
bound:        127.0.0.1, a port of its own: true
bound again:  InvalidState[]
the server:   hello, sockets
the client:   HELLO, SOCKETS
```

`-S inherit-network=y` grants the component the host's network. Commands say
`rontolisp`, the native binary; with the executable JAR it is
`java -jar ../../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar`.
