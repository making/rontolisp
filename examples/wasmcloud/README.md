# wasmCloud template ports

rontolisp ports of the [wasmCloud Rust templates](https://github.com/wasmCloud/wasmCloud/tree/main/templates),
built on `rontolisp:http-handler` (see the
[Serving HTTP guide](https://making.github.io/rontolisp/guides/http-handler.html)).
Each port keeps the routes and response bodies of the original template.

| Template | Port | interpreter | JVM | wasmtime serve | wasmCloud (`wash dev`) |
|---|---|---|---|---|---|
| http-hello-world | [`http-hello-world/app.lisp`](http-hello-world/app.lisp) | yes | yes | yes | yes |
| http-handler | [`http-handler/app.lisp`](http-handler/app.lisp) | yes | yes | yes | yes |
| http-client | [`http-client/app.lisp`](http-client/app.lisp) | yes | yes | yes | yes (allowlist the upstream host) |
| http-kv-handler | [`http-kv-handler/app.lisp`](http-kv-handler/app.lisp) | yes (in-memory) | yes (in-memory) | not yet ported to `wasi:keyvalue` | not yet ported to `wasi:keyvalue` |
| service-tcp | [`service-tcp/`](service-tcp/) | yes | yes | yes (http-api half; needs `-S cli=y -S tcp=y -S inherit-network=y`) | yes (both halves; service-leet runs as a v2 service) |
| http-api-with-distributed-workloads | not ported | - | - | - | - |

The `wasi:keyvalue` gap is not a missing capability: a served component can
import the interface, and
[`examples/wit/keyvalue/page-hits-server.lisp`](../wit/keyvalue/page-hits-server.lisp)
is a page-hit counter that does, keeping its counts on wasmCloud across
requests. This port has simply not been rewritten against it yet.

`service-tcp` runs on wasmCloud with both halves in one `wash dev`: wash
2.5.x provides `wasi:sockets` 0.3, and `service-leet.lisp` compiled with
`--component` exports `wasi:cli/run@0.3.0` -- exactly the shape of the
wasmCloud v2 service model -- so the directory's `.wash/config.yaml`
registers it as `dev.service_file` and the http-api component reaches it
over the workload's in-process virtual loopback. That virtual loopback is
also the one catch: inside a wasmCloud component, 127.0.0.1 never means the
machine's loopback, so a leet service running as a host process is
unreachable from there (non-loopback addresses connect over the real
network as usual). Under `wasmtime serve` the http-api half instead talks
to a real-loopback service-leet and needs `-S cli=y -S tcp=y
-S inherit-network=y` on top of the usual flags (see its header).

## Running

Every `app.lisp` carries its exact run commands in its header comment; the
pattern is the same for all of them:

```bash
# interpreter (blocking server on :8080)
rontolisp examples/wasmcloud/http-hello-world/app.lisp

# JVM class (self-contained: the server travels beside the class)
rontolisp examples/wasmcloud/http-hello-world/app.lisp -o App.class
java -cp . App

# WASI HTTP component under wasmtime serve (wasmtime 47+ for concurrent throughput)
rontolisp examples/wasmcloud/http-hello-world/app.lisp -o app.wasm --component
wasmtime serve -W gc=y -W exceptions=y app.wasm
```

Each component directory keeps a `.wash/config.yaml` whose `build.command`
compiles the example with the `rontolisp` binary (expected on the `PATH`;
`service-tcp` chains both halves and registers the service via
`dev.service_file`) and whose `dev.wasm_proposals` lists the proposals the
component needs.
`http-client` additionally sets `workload.allowedHosts` -- wash denies all
outgoing HTTP unless the upstream host is allowlisted.

## wasmCloud status (checked 2026-07-16): WORKS

Since the callback-async cutover the serve component uses only **base
`component-model-async`**: the handle export is a callback async lift, and every
stream/future body operation is the asynchronous (non-blocking) built-in variant
with a blocking `waitable-set.wait` park -- no synchronous built-ins, no stackful
lift, so none of the gated wasmtime features. **Released `wash` (2.5.2) hosts the
components** with `dev.wasm_proposals: [gc, exception-handling,
component-model-async]` (each template's `.wash/config.yaml` sets them): `wash dev`
in `http-handler/` serves and answers
`curl http://127.0.0.1:8000/` with "Hello from wasmCloud!".

(Historical note: the pre-cutover 0.3 components were rejected at parse time with
"synchronous stream.write requires the component model more async builtins
feature" -- wash has no switch for that wasmtime feature. The cutover removed the
dependency instead.)

## service-tcp

The original template demonstrates the wasmCloud v2 service model: a
long-running TCP service plus a stateless HTTP component in one host. The
port runs the same two programs either as two host processes
(interpreter/JVM) or inside one wasmCloud host:

```bash
# two host processes (interpreter; JVM per the file headers)
rontolisp examples/wasmcloud/service-tcp/service-leet.lisp &   # TCP :7777
rontolisp examples/wasmcloud/service-tcp/http-api.lisp &       # HTTP :8080
curl -X POST -d '{"payload":"Hello World"}' http://127.0.0.1:8080/task
# H3110 W0r1d

# one wasmCloud host (builds both halves, runs service-leet as a service)
cd examples/wasmcloud/service-tcp && wash dev                  # HTTP :8000
curl -X POST -d '{"payload":"Hello World"}' http://127.0.0.1:8000/task
# H3110 W0r1d
```
