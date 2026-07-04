# wasmCloud template ports: what could not be ported (examples/wasmcloud)

`examples/wasmcloud/` ports the wasmCloud Rust templates
(`templates/` in the wasmCloud repo) onto `rontolisp:http-handler`.
Three templates could not be ported fully; this note records why and what
would unblock each.

## http-kv-handler: WASM/wasmCloud need wasi:keyvalue (.todo/52)

The original stores through `wasi:keyvalue` with a pluggable backend
(in_memory / filesystem / NATS / Redis selected by `.wash/config.yaml`).
The port (`examples/wasmcloud/http-kv-handler/app.lisp`) implements only the
default in_memory backend as a global hash table, which works on the
interpreter and JVM because the process lives across requests.

It compiles under `--component`, but is useless there: `wasmtime serve`
instantiates the component per request, and instance-reusing hosts
(jco, wasmCloud) get their bump allocators reset per request by the serve
adapter, so the global store is empty on every request either way. The real
fix is the `wasi:keyvalue` binding planned in `.todo/52-wasi-keyvalue.md`;
once `open`/`get`/`set` exist, this example should switch to them (the
in-memory hash table then remains as the interpreter/JVM fallback or is
dropped).

## service-tcp: no WASM path for either half

The original demonstrates the wasmCloud v2 service model: a long-running TCP
service (`wasi:cli/run` + `wasi:sockets`) plus a stateless HTTP component in
one host, talking over the host's loopback network. The port
(`examples/wasmcloud/service-tcp/`) runs both halves as interpreter/JVM
processes only:

- `http-api.lisp` (serve + `rontolisp:tcp-connect`) cannot compile to a
  component: serve + `rontolisp:tcp-*` in one `--component` binary is a
  compile error (the serve bridge and the sockets adapter both claim the
  same import surface; see `.kb/tcp-sockets.md` and
  `.todo/49-combine-fetch-and-sockets-component.md` for the same-shape
  fetch+tcp limitation).
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
