# 554. A `wasmtime serve` test owns port 8080, so a developer machine fails it

Difficulty: Low

`WasmLispCompilerIntegrationTest.httpHandlerServesUnderWasmtimeServe` runs
`wasmtime serve ... serve.wasm` with no `--addr` and then curls
`http://127.0.0.1:8080/hello`. `wasmtime serve`'s default address is `0.0.0.0:8080`, and
`HostWasmtime` runs the binary on the HOST rather than in a container -- so the port is
the developer's own. On a machine already serving 8080 the bind fails, curl reaches
whatever else is listening, and the assertion reports that program's body instead:

```
expected: "GET /hello"
 but was: "<!doctype html>... HTTPステータス 404 ... Apache Tomcat/11.0.22 ..."
```

Found 2026-08-27 on macOS, where a local Tomcat held 8080. Nothing about the compiled
component is wrong; the test simply asked a question of the wrong server, and its failure
message names Tomcat, which reads like anything but a port conflict.

The sibling case two methods up already solved this -- it passes
`--addr 127.0.0.1:8093` for exactly this reason -- so the fix is to give every
`wasmtime serve` case its own address. What the item is really about is that a FIXED port
is a shared resource between the suite and the machine, and the class runs its methods in
parallel (`JUnit parallelism = 16`), so two serve cases on one hardcoded port would also
race each other:

- Give each `wasmtime serve` case a distinct port, the way `serve-opt.wasm` does, or take
  one from the OS (bind a `ServerSocket(0)`, read the port, close it, pass it to
  `--addr`) so the suite never depends on a number being free.
- The same applies to any other test that binds a well-known port. `rontolisp:http-handler`
  on the JVM/interpreter legs and the clack examples are the places to check
  (`.kb/http-server.md`).
- Whichever way it goes, the FAILURE has to say what happened. A curl that reaches a
  stranger should be distinguishable from a handler that answered wrong -- asserting the
  serve process actually bound (its log line, or a probe before the request) turns a
  mystifying Tomcat 404 into "port 8093 was taken".

Not a product bug: no backend, no emitted module and no example is affected. It is a test
that cannot run on a machine that is doing something else.
