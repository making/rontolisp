# wasmCloud template ports: what could not be ported (examples/wasmcloud)

`examples/wasmcloud/` ports the wasmCloud Rust templates
(`templates/` in the wasmCloud repo) onto `rontolisp:http-handler`.
Three templates could not be ported fully; this note records why and what
would unblock each.

## http-kv-handler: WASM/wasmCloud need wasi:keyvalue

(`.kb/wit.md`, `examples/wit/keyvalue/README.md`)

The original stores through `wasi:keyvalue` with a pluggable backend
(in_memory / filesystem / NATS / Redis selected by `.wash/config.yaml`).
The port (`examples/wasmcloud/http-kv-handler/app.lisp`) implements only the
default in_memory backend as a global hash table, which works on the
interpreter and JVM because the process lives across requests.

It compiles under `--component`, but is useless there: `wasmtime serve`
instantiates the component per request, and an instance-reusing host
(wasmCloud) gets its bump allocator reset per request by the serve
adapter, so the global store is empty on every request either way.

**UNBLOCKED 2026-07-14.** The fix this was waiting for has landed: a serve-mode
component may `rontolisp:wit-import` a user WIT interface, so a handler's state
can live in a real `wasi:keyvalue` store — `examples/wit/keyvalue/page-hits-server.lisp`
does exactly that and accumulates its counts on wasmCloud (`wash dev`, an
out-of-process provider) while running unchanged on the interpreter and the JVM
against a Lisp store. What is left here is only the PORT: rewrite
`examples/wasmcloud/http-kv-handler/app.lisp` against the real
`wasi:keyvalue/store` (vendored in `examples/wit/keyvalue/wit/keyvalue.wit`), add a
`.wash/config.yaml` (`gc` + `exception-handling` proposals), and flip its two `no
(needs wasi:keyvalue)` cells in `examples/wasmcloud/README.md`. Note wasmtime's own
`-S keyvalue=y` provider is rebuilt per instance, so under `wasmtime serve` the
store reads back empty each request — that column stays honest only if it says so.

## service-tcp: no wasmCloud path for the service half

The original demonstrates the wasmCloud v2 service model: a long-running TCP
service (`wasi:cli/run` + `wasi:sockets`) plus a stateless HTTP component in
one host, talking over the host's loopback network. The port
(`examples/wasmcloud/service-tcp/`) was verified with both halves as
interpreter/JVM processes; the WASM story has since diverged per half:

- `http-api.lisp` (serve + `rontolisp:tcp-connect`) **works under `wasmtime
  serve` now** (the `.todo/144` trap -- serve components never ran their top
  level -- was fixed 2026-07-17): under `wasmtime serve -W gc=y
  -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y` all five routes
  answer, including `POST /task` -> "H3110 W0r1d" end-to-end against an
  interpreter `service-leet`. On wasmCloud it still has no path: `wash dev`
  hosts the component and serves the non-tcp routes, but tcp connections
  fail there (the host advertises `wasi:sockets` 0.2 only; the exact
  failure shape -- a nil from a connect probe, a 500 from `POST /task` --
  and its cause are under verification in `.todo/145`). The README's
  wasmtime cell is `yes` (with the flags), the wasmCloud cell
  `no (host has no wasi:sockets 0.3)` -- wasmCloud gaining 0.3 sockets is
  what would flip it.
- `service-leet.lisp` compiles to a `wasmtime run` component (tcp works
  there), but wasmCloud's v2 service model expects `wasi:sockets` 0.2 from a
  `wasi:cli/run` world, while rontolisp's tcp built-ins are natively WASI
  0.3 (`wasi:sockets@0.3.0`) -- wash cannot host it. A 0.2 sockets fallback
  (or wasmCloud gaining 0.3 sockets) would unblock the service half.

## http-api-with-distributed-workloads: not ported at all

The original delegates work from an HTTP API to background worker components
over `wasmcloud:messaging` (`consumer::request` / a `handler` export, NATS
or in-process routing). rontolisp has no messaging binding and no way to
export a non-HTTP handler interface, so there is nothing to port onto yet.
Porting it would need:

1. a `wasmcloud:messaging/consumer` (or `wasi:messaging`) client built-in
   (interpreter/JVM could bridge to NATS; the component would import the
   interface), and
2. a way to export `wasmcloud:messaging/handler` from a rontolisp program
   (a serve-mode analogue for messaging: the `HttpHandlerInliner` /
   `%http-dispatch` pattern generalized to a second world).

## Verification-time notes (2026-07-04)

- httpbin.org (the upstream hardcoded by the http-client template) answered
  503 during verification; the port was verified by forwarding that 503 and
  separately against a local `examples/httpbin.lisp` upstream for the 200
  path. Nothing to fix in the example.
