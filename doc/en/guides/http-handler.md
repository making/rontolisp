# Serving HTTP (http-handler)

Hand-rolling HTTP over `read-line`/`write-line` (as the
[TCP Sockets guide](tcp-sockets.md) demonstrates with `http-hello.lisp`) is
instructive, but for a plain request/response server
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
does the parsing for you. You write a handler that takes a request property
list (`:method` / `:path` / `:headers` / `:body`) and returns a response
property list (`:status` / `:headers` / `:body`) — the same value model as
[`rontolisp:fetch`](http-fetch.md), just incoming instead of outgoing:

```console
(defun handle (request)
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

(rontolisp:http-handler 'handle 8080)
```

Save it as `app.lisp` (also shipped as
[`examples/http-handler.lisp`](https://github.com/making/rontolisp/blob/develop/examples/http-handler.lisp)),
then run it on any of the three supported backends below.

## On the interpreter

`http-handler` starts a blocking embedded HTTP server on port 8080 (one
virtual thread per request) and serves until the process is stopped with
`Ctrl-C`:

```console
$ rontolisp app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## Compiled to a JVM class

The same source compiles to a **JVM class** serving the same way. Unlike
other compiled rontolisp programs, the class is not self-contained: it
implements the embedded server's handler interface, so the rontolisp
executable JAR (`rontolisp-0.1.0-SNAPSHOT-exec.jar`, the same download as in
[Build & Install](../getting-started/build.md)) must be on the classpath when
running it:

```console
$ rontolisp app.lisp -o App.class
$ java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## Compiled to a WASI HTTP component

It also compiles to a **WASI HTTP component** that runs under
`wasmtime serve` (wasmtime 46+):

```console
$ rontolisp app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

There the module exports `wasi:http/incoming-handler` and the host owns the
socket, so the `port` argument is ignored. Note the command needs none of the
`component-model-async` flags that `wasmtime run` needs for a regular rontolisp
component: the serve component is plain WASI 0.2, so its only non-default host
requirement is the WebAssembly GC proposal (`-W gc=y`).

## Other WASI HTTP runtimes

Because the component only asks its host for `wasi:http` 0.2 plus wasm-GC,
wasmtime is not the only runtime that can serve it.

**jco** (the Bytecode Alliance's JavaScript toolchain, running on Node.js/V8 —
where wasm-GC is enabled by default) runs it with no extra configuration:

```console
$ npx @bytecodealliance/jco serve app.wasm --port 8080
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

**wasmCloud** (`wash` 2.x) runs it once the `gc` proposal is switched on. For
`wash dev`, point the project's `.wash/config.yaml` at the prebuilt component
and list the proposal (the no-op `build.command` skips wash's own build step):

```yaml
build:
  command: "true"
  component_path: app.wasm
dev:
  wasm_proposals:
    - gc
```

```console
$ wash dev
$ curl http://127.0.0.1:8000/hello
Hello from rontolisp!
GET /hello
```

`wash host` exposes the same switch as `--wasm-proposal gc` (or the
`WASH_WASM_PROPOSALS=gc` environment variable).

**Spin** (`spin up`) cannot run the component yet: its embedded wasmtime does
not enable the WebAssembly GC proposal that every rontolisp component needs,
and it offers no flag to turn it on.

## Limitations

On the JVM and WASI component backends, request and response headers are not
marshalled yet: the handler sees `:headers nil` and `:headers` in the response
is ignored. Only the interpreter passes headers through. See the
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
reference page for the details.

For the *client* side of HTTP, use `rontolisp:fetch` — see the
[HTTP Requests guide](http-fetch.md). To work at the raw socket level instead
(any TCP protocol, or TLS), see the [TCP Sockets guide](tcp-sockets.md).
