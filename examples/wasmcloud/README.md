# wasmCloud template ports

rontolisp ports of the [wasmCloud Rust templates](https://github.com/wasmCloud/wasmCloud/tree/main/templates),
built on `rontolisp:http-handler` (see the
[Serving HTTP guide](https://making.github.io/rontolisp/guides/http-handler.html)).
Each port keeps the routes and response bodies of the original template.

| Template | Port | interpreter | JVM | wasmtime serve | wasmCloud (`wash dev`) |
|---|---|---|---|---|---|
| http-hello-world | [`http-hello-world/app.lisp`](http-hello-world/app.lisp) | yes | yes | yes | yes |
| http-handler | [`http-handler/app.lisp`](http-handler/app.lisp) | yes | yes | yes | yes |
| http-client | [`http-client/app.lisp`](http-client/app.lisp) | yes | yes | yes (`-S http=y`) | yes |
| http-kv-handler | [`http-kv-handler/app.lisp`](http-kv-handler/app.lisp) | yes (in-memory) | yes (in-memory) | no (needs `wasi:keyvalue`) | no (needs `wasi:keyvalue`) |
| service-tcp | [`service-tcp/`](service-tcp/) | yes | yes | no (serve + tcp) | no (service model) |
| http-api-with-distributed-workloads | not ported | - | - | - | - |

The gaps in the last three rows are recorded in
`.todo/53-wasmcloud-template-gaps.md`.

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
wasmtime serve -W gc=y app.wasm          # http-client additionally needs -S http=y

# wasmCloud (wash 2.x; serves on :8000)
cd examples/wasmcloud/http-hello-world && wash dev
```

Each component directory has a `.wash/config.yaml` whose `build.command`
compiles `app.lisp` with the `rontolisp` binary (expected on the `PATH`) and
whose `dev.wasm_proposals` switches on the WebAssembly GC proposal that every
rontolisp component needs. `http-client` additionally sets
`workload.allowedHosts` -- wash denies all outgoing HTTP unless the upstream
host is allowlisted.

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
