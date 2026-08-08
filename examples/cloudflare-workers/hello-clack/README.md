# hello-clack — a **Clack application** on Cloudflare Workers, in three forms

The smallest thing this repository can show that is still a real
[Clack](https://github.com/fukamachi/clack) application: `worker.lisp` is
`ql:quickload`, one `defun`, and `clack:clackup`.

```bash
./build.sh          # worker.lisp -> src/app.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl http://localhost:8787/
Hello from Clack on Cloudflare Workers!
GET /

$ curl http://localhost:8787/anything
Hello from Clack on Cloudflare Workers!
GET /anything
```

## The whole program

```lisp
(ql:quickload '("clack" "clack-handler-cloudflare-workers"))

(defun app (env)
  (list 200 '(:content-type "text/plain; charset=utf-8")
        (list
         (format nil "Hello from Clack on Cloudflare Workers!~%~a ~a~%"
                 (getf env :request-method) (getf env :path-info)))))

(clack:clackup #'app
               :server :cloudflare-workers
               :use-thread nil
               :use-default-middlewares nil)
```

There is **no Worker-specific code in it**. `app` takes the Clack environment
plist and returns the Clack `(status headers body)` list, so the same function
runs on hunchentoot, on woo, under `wasmtime serve` and on the JVM, unchanged.
What makes it a Worker is the `:server` designator, and nothing else.

`:cloudflare-workers` is a built-in handler backend for a host that **calls you**
instead of handing you a socket. Its `run` binds nothing — it stores the
application and returns — and what replaces the socket is one WASM export,
`handle-request` (a JSON request string in, a JSON response string out), which
[`src/index.js`](src/index.js) calls. You do not declare that export:
`rontolisp:wasm-export` needs a literal name at compile time, which a `clackup`
call has none to give, so the compiler synthesizes it from a marker the handler
backend leaves behind. The [Clack guide](../../../doc/en/guides/clack.md) has
the full story.

The two keywords are properties of this host rather than boilerplate:

- **`:use-thread nil`.** Already the default on the WASM backends
  (single-threaded by construction) — but the interpreter and the JVM have
  threads, and `clackup` would otherwise store the application on one of them,
  racing the next form.
- **`:use-default-middlewares nil`.** lack's `backtrace` middleware exists to
  print a report to `*error-output*`, which a host-driven reactor does not have.

## What it costs

`../hello` is the floor: three exported functions, no clack, **563 bytes**. This
directory is the other end, and the difference is not the adapter — it is clack
and lack being in the module so that `app` can be an ordinary Clack application.

| | [`../hello`](../hello) | this | [`../httpbin-clack`](../httpbin-clack) |
| --- | --- | --- | --- |
| the Lisp | 3 `wasm-export`ed functions | a Clack application + `clackup` | the same, with five echo endpoints |
| module | 563 B | 459,059 B raw / **121,525 B gzip** | 474,150 B / 124,756 B gzip |
| imports | zero | **zero** — instantiated with `{}`, no WASI shim | zero |
| `_initialize` | none (no top-level forms) | ~12 ms — clack's load time, `clackup` included | ~12 ms |
| warm request | | **0.015 ms** | 0.024 ms |

Measured on node 24 (V8, the same engine family as workerd, 2026-08-08) driving
[`src/index.js`](src/index.js)'s boundary code against this exact `src/app.wasm`.
On the real edge, `wrangler deploy` reported **1608.77 KiB upload / 358.27 KiB
gzip** and a **Worker Startup Time of 30 ms** — measured before the module
shrank to today's 448 KB; the next deploy will report the smaller bundle — and
both routes answer there — verified after deploying, not inferred.

So the cost of "it is a real Clack application" is startup and bundle size, paid
once per isolate; the per-request cost is the Lisp call plus the string
boundary. If a program will only ever run on a Worker, `../hello` is the
cheaper shape — and if it needs to run on a Worker *and* on a real server,
this is what that costs.

## Developing without Cloudflare

The exported entry point only exists on the WASM backends, but what sits under
it does not: `clack.handler.cloudflare-workers:dispatch` is an ordinary function
of a JSON string, and it is exactly what the synthesized export calls. So the
whole Worker runs on the interpreter:

```bash
rontolisp check.lisp
```

and identically on the JVM and the WASM backends, which
[`examples/examples.yaml`](../../examples.yaml) pins:

```bash
rontolisp check.lisp -o Check.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Check
rontolisp check.lisp -o check.wasm && wasmtime run -W gc -W exceptions=y check.wasm
```

The two lines it prints before the first `-->` are upstream clack's: `clackup`
announces the server it is about to start, and `clack.handler:run` announces
debug mode. On a Worker (`--no-wasi`) standard output is a **sink**, so those
bytes are discarded rather than trapping the instance; locally they are not.
Pass `:silent t :debug nil` to quiet them.

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | The whole program — three forms. This is what `build.sh` compiles. |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight, on any backend — and what the examples manifest runs. |
| [`src/index.js`](src/index.js) | The whole Worker: `Request` -> JSON -> Lisp -> JSON -> `Response`. |
| `src/app.wasm` | The compiled module. A build product — run `./build.sh` first. |

[`../httpbin-clack`](../httpbin-clack) is this example grown up: the same
handler backend and the same envelope, but with five endpoints, a request body,
and an application that is byte-identical to
[`examples/net/httpbin-clack.lisp`](../../net/httpbin-clack.lisp) — which serves
the very same code on a real HTTP server, so the two can be compared with
`curl`.

## Limitations

The Worker sandbox and the `--no-wasi` build, exactly as in
[`../httpbin`](../httpbin/README.md#limitations): no input, time or `random` in
the Lisp (they trap), no filesystem, no `rontolisp:fetch` (use JavaScript's
`fetch()` in `src/index.js`). Printing does not trap — it is discarded. And a
runtime `(ql:quickload ...)` cannot work: the one at the top of `worker.lisp` is
resolved at **compile** time and inlined into the module, which is why the first
`./build.sh` needs network and later ones do not.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello-clack/build.sh
```
