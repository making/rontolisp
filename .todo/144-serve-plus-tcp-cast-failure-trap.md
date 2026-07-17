# serve + tcp: `tcp-connect` traps with `cast failure` under `wasmtime serve`

**Status:** open, not started. A real bug, found 2026-07-17 while re-verifying
`.todo/053`'s stale "serve + tcp cannot compile" claim. Compiling the pair is
fine now (commit c84708c); calling tcp from inside a served handler is not.

## Repro (four lines, no JSON, no async, no I/O)

Start any TCP listener on 7777 that accepts and closes, then:

```lisp
(defun handle (request)
  (let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
    (close sock)
    (list :status 200 :headers (list (cons "content-type" "text/plain")) :body "connected")))
(rontolisp:http-handler 'handle 8080)
```

```
rontolisp repro.lisp -o repro.wasm --component     # compiles, ~199 KB
wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y repro.wasm
curl http://127.0.0.1:8080/                        # 500
```

The component instantiates and serves ("Serving HTTP on http://0.0.0.0:8080/");
the request dies with:

```
worker failed: error while executing at wasm backtrace: ...
Caused by:
    wasm trap: cast failure
error: worker trapped or panicked
```

Same trap with the tcp call inside an `rontolisp:async-defun` (the
`WasmSocketsRewrite` await-promotion path), so it is not specific to the sync
surface's shape at the call site.

## What isolates it to the serve path

- **The identical tcp code works under `wasmtime run`.** `(let ((sock
  (rontolisp:tcp-connect "127.0.0.1" 7777))) (close sock) (print "connected"))`
  compiled with `--component` prints `"connected"` under
  `wasmtime run -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y`.
- **The import is byte-identical between the two.** `wasm-tools component wit`
  shows the same `wasi:sockets@0.3.0` import in the working `run` component and
  the trapping `serve` one.
- Everything in the handler that does NOT touch tcp works under serve: the
  `examples/wasmcloud/service-tcp/http-api.lisp` port answers `GET /` 200,
  `GET /nope` 404, `GET /task` 405 and a malformed `POST /task` 400; only the
  route that reaches `tcp-connect` 500s.

So the two halves each work alone and the failure is in their composition under
the serve callback driver.

## First place to look

The sync tcp surface is `%future-force` (`.kb/tcp-sockets.md`), i.e. a blocking
event-loop park, while a served handler runs under the CALLBACK async driver: a
pending task returns the packed WAIT code to the host rather than blocking
(`.kb/async-await.md`, `.kb/wasi-component.md`). A blocking park inside a
callback-driven task is exactly the shape that should not typecheck at runtime,
and `cast failure` is a `ref.cast` on a GC struct -- so the likely story is the
force path reading a task/future record of the wrong variant under serve. Two
concrete checks before designing anything: which `ref.cast` traps (map the
backtrace's wasm function indices with `wasm-tools dump`), and whether
`rontolisp:fetch` -- which is async-native inside serve and demonstrably works
(`componentFetchInsideServe`) -- takes a different path than `%future-force`.

## Correct the wrong diagnosis this bug is currently filed under

`examples/wasmcloud/README.md` and `examples/wasmcloud/service-tcp/http-api.lisp`
both blame the host:

> `wasmtime serve` does not wire the `wasi:sockets` 0.3 `tcp-socket` resource,
> and wasmCloud's host provides `wasi:sockets` 0.2 only, so the component traps
> on the first tcp call.

The first half is **false**, and it matters: it sends the reader to wasmtime
instead of to rontolisp. That text comes from omitting `-S cli=y`, whose absence
gives a link-time error (`instance export 'tcp-socket' has the wrong type:
resource implementation is missing`) that reads like a missing host feature.
With `-S cli=y` the resource IS wired -- the component instantiates, serves, and
answers four routes correctly. The trap is ours. (The wasmCloud-0.2 half was not
tested here; `.todo/053` keeps it for the `service-leet` side, where it is about
the v2 service model, not this trap.)

Fixing that prose is part of this todo, not `.todo/053`.

## Definition of done

- The four-line repro returns 200 under `wasmtime serve`.
- `examples/wasmcloud/service-tcp/http-api.lisp` answers `POST /task` with a
  valid payload end-to-end under `wasmtime serve` (its other four routes already
  pass), and its header + `examples/wasmcloud/README.md` say what is actually
  true.
- An integration case pins it next to `httpHandlerCallsAUserWitImportUnderWasmtimeServe`
  in `WasmLispCompilerIntegrationTest`, i.e. one that actually RUNS it under
  wasmtime. This is how the bug survived c84708c: serve + tcp is not
  uncovered, it is covered by `WasmLispCompilerTest.httpHandlerWithTcpCompilesInServeMode`,
  which asserts only `compile(program)).isNotEmpty()` and never leaves the JVM.
  A compile-only assertion on a pair whose whole risk is in their RUNTIME
  composition is false confidence -- it went green the day the compile error
  was removed and would go green again tomorrow.
- `.todo/053`'s service-tcp bullet drops its pointer here and records whatever
  the README ends up saying.

## Note

`examples/wasmcloud/` has NO entries in `examples/examples.yaml` (no wasmcloud
rows at all), so none of these examples is exercised by `ExamplesE2eTest`. That
is a separate gap worth closing once this works.
