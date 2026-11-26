# A visit counter in a session: `hello-session/`

[`HelloSession.java`](src/main/java/HelloSession.java) is
[`hello-servlet`](../hello-servlet/README.md) with a session: one lambda servlet counting
the visits of the session the client's cookie names. The sessions are not in the server's
memory: [`servlet-httpserver-keyvalue`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver-keyvalue),
a dependency of the pom, is the store the server finds, and keeps them in
`wasi:keyvalue/store@0.2.0-draft2` ([Sessions in `wasi:keyvalue`](../../../doc/en/java/servlet.md#sessions-in-wasikeyvalue)).
The file names no store: the library on the class path, or the source path, is the switch.

```bash
cd examples/java/hello-session
mvn package               # target/hello-session.wasm, a component importing wasi:keyvalue
mvn exec:exec             # JDK: the library's store in memory
curl -c jar -b jar http://localhost:8080/   # Hello World! Visit 1 of this session.
curl -c jar -b jar http://localhost:8080/   # Hello World! Visit 2 of this session.
```

Where the store is depends on where the program runs:

| Run | The store |
| --- | --- |
| a JDK, the interpreter, the JVM | the library's `MemoryKeyValueStore`, as long as the process |
| the component on [sluice-wicket-runtime-wasi](https://github.com/making/sluice/tree/main/sluice-wicket-runtime-wasi) (`?wasi=keyvalue`) | the host's, a SQLite file: a visit counts on in the next instance, and after a restart |
| a Cloudflare Worker: [`cloudflare-workers/hello-session-one-source`](../../cloudflare-workers/hello-session-one-source) | a Workers KV namespace, which the generated glue answers `wasi:keyvalue` with |

`wasmtime serve -S keyvalue=y` (wasmtime 51) links the first draft of `wasi:keyvalue`
only and refuses the component at its start; it keeps nothing between requests either.
