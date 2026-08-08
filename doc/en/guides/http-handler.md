# Serving HTTP (http-handler)

Hand-rolling HTTP over `read-line`/`write-line` (as the
[TCP Sockets guide](tcp-sockets.md) demonstrates with `http-hello.lisp`) is
instructive, but for a plain request/response server
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
does the parsing for you. You write a handler that takes the Clack
environment property list (`:request-method` / `:path-info` /
`:query-string` / `:headers` / `:raw-body` / ...) and returns the Clack
response list `(status headers body)` — the protocol of
[Clack Web Applications](clack.md), which is why a Clack application is
served with zero per-request conversion:

```console
(defun handle (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf env :request-method) (getf env :path-info)))))

(rontolisp:http-handler 'handle 8080)
```

Save it as `app.lisp` (also shipped as
[`examples/net/http-handler.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/http-handler.lisp)),
then run it on any of the three supported backends below.

## The handler contract

The handler receives Clack's environment property list, with these keys —
always all present:

| env key | value |
|---------|-------|
| `:request-method` | the method as an upcased interned keyword (`:GET`, `:POST`, ...), so `(eq m :POST)` works |
| `:script-name` | always `""` |
| `:path-info` | the percent-decoded request path |
| `:query-string` | the raw text after the first `?`, or `nil` when there is none |
| `:server-name` / `:server-port` | from the `Host` header when present, otherwise the listener's |
| `:server-protocol` | a keyword, e.g. `:HTTP/1.1` |
| `:request-uri` | the raw request target verbatim (still percent-encoded, query included) |
| `:url-scheme` | `"http"` or `"https"` |
| `:remote-addr` / `:remote-port` | the real peer on the interpreter and the JVM; `nil` on the WASI component (`wasi:http@0.3.0` exposes no peer accessor) |
| `:headers` | an `equal` hash table keyed by **lowercased** header names — look up with `(gethash "content-type" (getf env :headers))`; repeated headers join with `", "`; never `nil` |
| `:content-type` / `:content-length` | from that table (`nil` when absent; `:content-length` an integer) |
| `:raw-body` | the request body (below) |

By default `:raw-body` is rontolisp's **asynchronous** stream: a handler that
reads it drains it with
`(rontolisp:await (rontolisp:read-all (getf env :raw-body)))` and must be an
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md).
With the optional directive argument
`(rontolisp:http-handler 'handle 8080 :raw-body :buffered)` the body is
instead read in full up front and handed over as a **synchronous** in-memory
bivalent stream — readable with `read-line`/`read-char` *and*
`read-byte`/`read-sequence`, with a real `file-position` — which is what a
Clack application (lack-request, http-body) needs; a bodiless request then
gets `:raw-body nil`.

The handler returns Clack's positional response list `(status headers body)`:

- `status` — a **required** integer; a non-integer car signals an error.
- `headers` — a keyword plist (`'(:content-type "text/plain")`, the idiomatic
  form) or a dotted alist — accepted so a [`rontolisp:fetch`](http-fetch.md)
  result's `:headers` can be passed straight through. Repeated names each
  become their own header line (repeated `:set-cookie` is correct by
  construction); `content-length`/`transfer-encoding` are dropped (the server
  computes them); `nil` is fine.
- `body` — a **list of strings** (joined), `nil` or omitted (an empty body —
  the two-element `(status headers)` form is valid), an `(unsigned-byte 8)`
  vector, or a rontolisp stream (e.g. a proxied fetch body). A **bare string
  signals an error** — deliberately, and faithfully to Clack: a pathname body
  means "serve this file" there, and a rontolisp pathname *is* its
  namestring, so accepting strings would make a static-file middleware serve
  a file's *path* as its contents. A function response is supported in
  Clack's delayed form only —
  `(lambda (responder) ... (funcall responder (list 200 nil (list "later"))))`
  — and the streaming-writer form is refused.

One migration hazard is worth spelling out for handlers written against the
pre-Clack contract: the response side fails loudly (the errors above), but
the request side fails silently — `(getf env :method)` in a half-migrated
handler just returns `nil`.

The *client* side is unchanged: [`rontolisp:fetch`](http-fetch.md) still
yields its `(:status <integer> :headers <alist> :body <stream>)` result
plist.

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
`wasmtime serve` (wasmtime 47+ — 46 also serves it, but collapses under
concurrent load; see the throughput section below):

```console
$ rontolisp app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

There the module exports `wasi:http/handler@0.3.0` (the async WASI 0.3 HTTP
world) and the host owns the socket, so the `port` argument is ignored. The
flags enable what the component actually uses: the WebAssembly GC proposal
(`-W gc=y`) and the exception-handling proposal (`-W exceptions=y`, which the
Lisp-written HTTP glue uses to detect end-of-body). The handler is lifted as a
**callback async** export: a handler that suspends (awaiting a timer, a fetch
or a body read) hands control back to the host, which delivers each completion
event through the component's callback — all of it part of the base
component-model async ABI, which is default-on in wasmtime 46+, so no gated
feature flags are needed. The response is still delivered mid-task through
`canon task.return`, and the body streams after it.

## Other WASI HTTP runtimes

The component asks its host for `wasi:http` **0.3** (async) plus wasm-GC.
wasmtime 46+ serves it, and so does **wasmCloud**: `wash` 2.5.2 runs it with
`wash dev`, given
`dev.wasm_proposals: [gc, exception-handling, component-model-async]` in the
project manifest. Install it with
`curl -fsSL https://wasmcloud.com/sh | bash` — verified on wash 2.6.1. (Binaries
picked by tag from the separate `wasmCloud/wash` repository are a different, older
line: 2.0.0-rc.x offers `wasi:http` **0.2** only and rejects the component while
extracting its interfaces.)

**Spin** runs it too, from the
[canary build](https://github.com/spinframework/spin/releases/tag/canary)
(4.1.0-pre0) on — its embedded
wasmtime is 47, which enables the WebAssembly GC and exception-handling
proposals by default, so no flag is needed. Drop a `spin.toml` beside the
program:

```toml
spin_manifest_version = 2

[application]
name = "rontolisp-http-handler"
version = "0.1.0"

[[trigger.http]]
route = "/..."
component = "hello"

[component.hello]
source = "app.wasm"

[component.hello.build]
command = "rontolisp app.lisp -o app.wasm --component"
```

```console
$ spin build && spin up
Serving http://127.0.0.1:3000
$ curl http://127.0.0.1:3000/hello
Hello from rontolisp!
GET /hello
```

Spin owns the socket and listens on **3000**, so the `port` argument is ignored
here as well. A handler that calls [`rontolisp:fetch`](http-fetch.md) also needs
the upstream host on the component's `allowed_outbound_hosts` — Spin denies
outbound HTTP by default:

```toml
[component.dog]
source = "app.wasm"
allowed_outbound_hosts = ["https://dog.ceo"]
```

Released Spin **4.0.2 cannot** run the component. Its embedded wasmtime is 44,
which speaks the `wasi:http@0.3.0-rc-2026-03-15` snapshot rather than the
released `wasi:http@0.3.0`, so the imports fail to link even with GC turned on
(and the released 4.0.2 binary has no switch to turn GC on: the
`--experimental-wasm-feature` option is compiled into canary builds only).
**jco** cannot run it either — it does not implement the 0.3 async ABI.

## Query strings

`:path-info` carries the (percent-decoded) path only, so route comparisons
are exact. When the request has a query string it arrives separately under
`:query-string` — the raw text after the first `?`, or `nil` when there is
none. Parse it with the query-string functions of the URL library,
[`rontolisp:query-param`](../reference/functions/rontolisp-query-param.md) and
[`rontolisp:query-params`](../reference/functions/rontolisp-query-params.md)
(both url-decode keys and values, and both accept `nil`):

```console
(defun handle (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "Hello, ~a!~%"
                      (or (rontolisp:query-param (getf env :query-string) "name")
                          "world")))))

(rontolisp:http-handler 'handle 8080)
```

```console
$ curl 'http://127.0.0.1:8080/greet?name=ronto%20lisp'
Hello, ronto lisp!
$ curl http://127.0.0.1:8080/greet
Hello, world!
```

## Calling other services from a handler

[`rontolisp:fetch`](http-fetch.md) works inside a served handler on all three
backends, enabling the classic proxy / aggregator shape. A handler that awaits
is an asynchronous function, so define it with
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md)
instead of `defun`:

```console
(rontolisp:async-defun handle (env)
  (let ((res (rontolisp:await
              (rontolisp:fetch "http://127.0.0.1:9000/upstream"))))
    (list (getf res :status) (getf res :headers) (getf res :body))))

(rontolisp:http-handler 'handle 8080)
```

The fetch result's `:headers` alist goes into the response's `headers` slot
as is, and its `:body` stream into the `body` slot — the server drains it.

On the WASI component backend the outgoing-request machinery rides along in
the same component — serve and serve+fetch are one component shape, importing
`wasi:http/client@0.3.0`, which `wasmtime serve` provides by default (no
`-S http=y` needed):

```console
$ rontolisp proxy.lisp -o proxy.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y proxy.wasm
```

A complete example is
[`examples/net/dog-fetcher.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/dog-fetcher.lisp),
a reproduction of
[wasmCloud's dog-fetcher example](https://wasmcloud.com/docs/v1/examples/rust/component/dog-fetcher/):
every request fetches a random dog picture URL from the dog.ceo API and
answers it as JSON.

## Keeping State: a store, not a global

On the interpreter and the JVM the server is one long-lived process, so a global
hash table survives between requests. **A served component's does not** — and the
way it does not is worse than "it resets every time". How long an instance lives
is the host's decision, and the hosts disagree:

| host | instance lifetime |
|---|---|
| `wasmtime serve` | 128 requests, then retired (`--max-instance-reuse-count`) |
| Spin | 128 requests (it inherits wasmtime's default) |
| wasmCloud `wash dev` | 1 request — always a fresh instance |

So a global neither survives the run nor resets per request: under wasmtime and
Spin the top level runs again on every 128th request, and everything the handler
accumulated in a global vanishes with it. Treat top-level side effects as
idempotent, and keep anything that must survive outside the component.

The way to keep state is therefore to put it outside the component — in a WIT
interface the handler *calls*, bound with
[`rontolisp:wit-import`](../reference/functions/rontolisp-wit-import.md). A served
component imports it alongside its fixed `wasi:http` surface:

```console
(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0-draft"
                      :package kv)

(defun handle (env)
  (let* ((page (getf env :path-info))
         (bucket (kv:open ""))
         (seen (kv:bucket-get bucket page))
         (hits (+ 1 (if seen (parse-integer seen) 0))))
    (kv:bucket-set bucket page (princ-to-string hits))
    (list 200 nil (list (format nil "~a: ~a hits~%" page hits)))))

(rontolisp:http-handler 'handle 8080)
```

```console
$ rontolisp page-hits-server.lisp -o server.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y server.wasm
```

The same source runs on the interpreter and the JVM, where a
[provider](../reference/functions/rontolisp-wit-provide.md) written in Lisp
answers the interface instead. Whether the counts *survive* on a component is the
host's business: wasmtime's built-in key-value provider is an in-memory store that
starts empty on every request (verified: each request reports 1 hit), while a host
that links an out-of-process provider — wasmCloud (`wash dev`), say — keeps them.
The
worked example is
[`examples/wit/keyvalue`](https://github.com/making/rontolisp/tree/main/examples/wit/keyvalue).

## Throughput, and what the component pays for

The three backends are in the same league on a trivial handler. Measured on one
machine (16 concurrent connections, 10 s closed loop, a handler that answers
`"Hello " + :path-info`; wasmtime 47.0.2, `wasmtime serve` at its defaults):

| backend | requests/s | mean | p99 |
|---|---|---|---|
| interpreter | 33 900 | 0.47 ms | 0.99 ms |
| JVM class | 36 600 | 0.44 ms | 0.88 ms |
| WASI component | 24 500 | 0.65 ms | 1.19 ms |

The component row needs **wasmtime 47 or newer**. On wasmtime 46 a *failing*
runtime type test — the ordinary misses of a dynamic language's type dispatch —
is a call into the host that takes an engine-global lock. One connection merely
pays it (about 10%); concurrent connections contend for it, and throughput
*falls* as connections are added — to roughly a fifteenth at 16 connections.
wasmtime 47 checks these types inline (every type rontolisp emits is final,
which is exactly the shape its fast path needs), and the collapse disappears.

The component's gap is **instantiation**, not the handler: the host runs the
whole top level (`_start`) once per instance, and it retires an instance every
`--max-instance-reuse-count` requests. Lowering that knob makes the cost visible
— at `--max-instance-reuse-count 1` the same component drops to roughly a third
of the throughput above, because every request pays a full instantiation.

Two consequences worth knowing:

- **Where the top level goes matters, and how much depends on the host.** Work
  done at top level is paid once per instance — amortized over 128 requests
  under wasmtime and Spin, but paid on *every* request under wasmCloud, where
  the same handler serves 7 900 rps against wasmtime's 24 500. A
  `ql:quickload "clack"` program and a bare `rontolisp:http-handler` one serve
  at nearly the same rate under wasmtime for exactly this reason, and at
  visibly different rates under wasmCloud.
- **`--optimize` is about size, not speed here.** It tree-shakes the compiled
  core module (a serve component loses a few percent; a non-serve component can
  lose 90%), which shortens instantiation slightly, but it does not change the
  steady-state per-request cost.

## Limitations

Request and response headers are marshalled on every backend, including the WASI
component: the handler reads `:headers` (an `equal` hash table keyed by
lowercased header names) from the environment and the response's `headers`
element is written back.

Inside a served component handler, `random`, the time built-ins and `print`
(to the host's stdout) all work — the component bridges them to the
`wasi:random`, `wasi:clocks` and `wasi:cli` interfaces every `wasi:http` host
provides. `uiop:getenv` reads the host environment too — a served component
imports `wasi:cli/environment@0.3.0`, so `wasmtime serve --env NAME=value`
(or `-S inherit-env=y`) reaches the handler — and file streams are
unavailable. See the
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
reference page for the details.

For the *client* side of HTTP, use `rontolisp:fetch` — see the
[HTTP Requests guide](http-fetch.md). To work at the raw socket level instead
(any TCP protocol, or TLS), see the [TCP Sockets guide](tcp-sockets.md).
