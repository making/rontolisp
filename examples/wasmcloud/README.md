# wasmCloud template ports

rontolisp ports of the [wasmCloud Rust templates](https://github.com/wasmCloud/wasmCloud/tree/main/templates),
built on `rontolisp:http-handler` (see the
[Serving HTTP guide](https://making.github.io/rontolisp/guides/http-handler.html)).
Each port keeps the routes and response bodies of the original template.

| Template | Port | interpreter | JVM | wasmtime serve | wasmCloud (`wash dev`) |
|---|---|---|---|---|---|
| http-hello-world | [`http-hello-world/app.lisp`](http-hello-world/app.lisp) | yes | yes | yes | not yet (wasi:http@0.3 — see below) |
| http-handler | [`http-handler/app.lisp`](http-handler/app.lisp) | yes | yes | yes | not yet (wasi:http@0.3 — see below) |
| http-client | [`http-client/app.lisp`](http-client/app.lisp) | yes | yes | yes | not yet (wasi:http@0.3 — see below) |
| http-kv-handler | [`http-kv-handler/app.lisp`](http-kv-handler/app.lisp) | yes (in-memory) | yes (in-memory) | not yet ported to `wasi:keyvalue` | not yet ported to `wasi:keyvalue` |
| service-tcp | [`service-tcp/`](service-tcp/) | yes | yes | no (serve + tcp) | no (service model) |
| http-api-with-distributed-workloads | not ported | - | - | - | - |

The gaps in the last three rows are recorded in
`.todo/53-wasmcloud-template-gaps.md`. The `wasi:keyvalue` one is no longer a
missing capability -- a served component can import the interface, and
[`examples/wit/keyvalue/page-hits-server.lisp`](../wit/keyvalue/page-hits-server.lisp)
is a page-hit counter that does, keeping its counts on wasmCloud across requests.
This port has simply not been rewritten against it yet.

## Running

Every `app.lisp` carries its exact run commands in its header comment; the
pattern is the same for all of them:

```bash
# interpreter (blocking server on :8080)
rontolisp examples/wasmcloud/http-hello-world/app.lisp

# JVM class (running it needs the rontolisp jar on the classpath)
rontolisp examples/wasmcloud/http-hello-world/app.lisp -o App.class
java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App

# WASI HTTP component under wasmtime serve (wasmtime 46+)
rontolisp examples/wasmcloud/http-hello-world/app.lisp -o app.wasm --component
wasmtime serve -W gc=y -W exceptions=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y app.wasm
```

Each component directory keeps a `.wash/config.yaml` whose `build.command`
compiles `app.lisp` with the `rontolisp` binary (expected on the `PATH`) and
whose `dev.wasm_proposals` lists the proposals the component needs.
`http-client` additionally sets `workload.allowedHosts` -- wash denies all
outgoing HTTP unless the upstream host is allowlisted.

## wasmCloud status (checked 2026-07-16)

Since the `wasi:http@0.3.0` cutover the serve component is an **async 0.3**
component (`wasi:http/handler@0.3.0`, stackful async lift, synchronous
`stream<u8>`/`future<T>` body built-ins). wasmCloud **does host WASI 0.3**
([runtime docs](https://wasmcloud.com/docs/runtime/#wasi-03), the
[2.5.0 release post](https://wasmcloud.com/blog/wasmcloud-2-5-0-release/):
"always WASI 0.3-enabled", `wasi:http` finalized at 0.3.0) -- but it cannot
load a rontolisp component yet:

- **Released `wash` (2.5.2, the latest GitHub release -- what both
  `brew install wash` and `curl -fsSL https://wasmcloud.com/sh | bash`
  deliver)** rejects the component while parsing it, with every relevant
  proposal enabled (`dev.wasm_proposals: [gc, exception-handling,
  component-model-async]`): `synchronous stream.write requires the component
  model more async builtins feature`.
- **`wash` built from wasmCloud `main` (2.5.3-dev)** -- whose `wash dev` host
  additionally advertises `wasi:http/types,handler@0.3.0` in its interface
  list -- rejects the same way at interface extraction.

The distinction that matters: `component-model-async` and
`more-async-builtins` are SEPARATE wasmtime features. rontolisp components
use the *synchronous* stream/future canonical built-ins -- the substrate of
the stackful "write synchronous Lisp, run as an async task" design, and the
reason wasmtime itself needs `-W component-model-more-async-builtins=y` --
and wash exposes no way to enable that feature: its proposal vocabulary has
no entry for it, on any version, and the parse-time rejection fires before
the engine is even configured. So "components targeting P3 worlds are
compatible" holds only for components that avoid the synchronous built-ins.
The gap is a small upstream one in wash's parsing/engine configuration, not
in the component and not a version mismatch (the RC-era `--features wasip3`
build flag no longer exists; `main`'s P3 path targets final 0.3.0).
Re-check after the next wasmCloud release; until then, `wasmtime serve`
(46+) is the component host.

## service-tcp

The original template demonstrates the wasmCloud v2 service model: a
long-running TCP service plus a stateless HTTP component in one host. The
port is two rontolisp programs run as two processes (interpreter/JVM only):

```bash
rontolisp examples/wasmcloud/service-tcp/service-leet.lisp &   # TCP :7777
rontolisp examples/wasmcloud/service-tcp/http-api.lisp &       # HTTP :8080
curl -X POST -d '{"payload":"Hello World"}' http://127.0.0.1:8080/task
# H3110 W0r1d
```
