# hello-clack — a **Clack application** on Cloudflare Workers, in three forms

The smallest thing this repository can show that is still a real
[Clack](https://github.com/fukamachi/clack) application: `worker.lisp` is
`ql:quickload`, one `defun`, and `clack:clackup`.

```bash
./build.sh          # worker.lisp -> src/worker.wasm
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
(ql:quickload '("clack" "clack-handler-reactor"))

(defun app (env)
  (list 200 '(:content-type "text/plain; charset=utf-8")
        (list
         (format nil "Hello from Clack on Cloudflare Workers!~%~a ~a~%"
                 (getf env :request-method) (getf env :path-info)))))

(clack:clackup #'app
               :server :reactor
               :use-thread nil)
```

There is **no Worker-specific code in it**. `app` takes the Clack environment
plist and returns the Clack `(status headers body)` list, so the same function
runs on hunchentoot, on woo, under `wasmtime serve` and on the JVM, unchanged.

`:reactor` is a built-in handler backend that is **host-driven on every
backend**: its `run` binds nothing — it stores the application and
returns — and what replaces the socket is one WASM export, `handle-request` (a
JSON request string in, a JSON response string out), which
[`src/index.js`](src/index.js) calls. You do not declare that export:
`rontolisp:wasm-export` needs a literal name at compile time, which a `clackup`
call has none to give, so the compiler synthesizes it from a marker the handler
backend leaves behind. The [Clack guide](../../../doc/en/guides/clack.md) has
the full story.

A Worker does not strictly need this designator any more: `:server :rontolisp`
serves on every target's own transport — a socket on the interpreter and the
JVM, `wasmtime serve` under `--component`, and this same synthesized export
under `--no-wasi` — which is how
[`../httpbin-clack-one-source`](../httpbin-clack-one-source) deploys
`examples/net/httpbin-clack.lisp` unchanged. What `:reactor` still says is
"host-driven *everywhere*": on the interpreter it stores the
application instead of binding a socket, which is what lets
[`check.lisp`](check.lisp) drive the whole Worker through `dispatch` with no
Cloudflare in sight.

The one keyword is a property of the other backends rather than boilerplate:
**`:use-thread nil`** is already the default on the WASM backends
(single-threaded by construction) — but the interpreter and the JVM have
threads, and `clackup` would otherwise store the application on one of them,
racing the next form. `clackup`'s default middlewares stay on: lack's
`backtrace` middleware prints its report to `*error-output*`, which under
`--no-wasi` is a sink (discarded, not a trap) and everywhere else is real
standard error.

## What it costs

`../hello` is the floor: three exported functions and no clack. This directory
is the other end, and the difference is not the adapter — it is clack and lack
being in the module so that `app` can be an ordinary Clack application.

| | [`../hello`](../hello) | this | [`../httpbin-clack`](../httpbin-clack) |
| --- | --- | --- | --- |
| the Lisp | 3 `wasm-export`ed functions | a Clack application + `clackup` | the same, with five echo endpoints |
| imports | zero | **zero** — instantiated with `{}`, no WASI shim | zero |
| `_initialize` | none (no top-level forms) | ~5 ms — clack's load time, `clackup` included | ~5 ms |
| warm request | | **0.013 ms** | 0.038 ms |

Measured on node 24 (V8, workerd's engine family) driving
[`src/index.js`](src/index.js)'s boundary code against this `src/worker.wasm`.
Module sizes: the
[size report](../../../size-report/results/cloudflare-workers.md). On the real
edge both routes answer, verified after deploying, and `wrangler deploy`
reported a Worker Startup Time of 30 ms — taken while `src/index.js`
instantiated at module scope; it now instantiates on the first request (a Worker
forbids generating random values in the global scope, and the module has to be
seeded before `_initialize`), so that work is paid by request one.

So the cost of "it is a real Clack application" is startup and bundle size, paid
once per isolate; the per-request cost is the Lisp call plus the string
boundary. If a program will only ever run on a Worker, `../hello` is the cheaper
shape — and if it needs to run on a Worker *and* on a real server, this is what
that costs.

## Developing without Cloudflare

The exported entry point only exists on the WASM backends, but what sits under
it does not: `clack.handler.reactor:dispatch` is an ordinary function of a JSON
string, and it is exactly what the synthesized export calls. So the
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
| `src/worker.wasm` | The compiled module. A build product — run `./build.sh` first. |

[`../httpbin-clack`](../httpbin-clack) is this example grown up: the same three
forms with five echo endpoints in the middle, and a `check.lisp` that drives
them the same way. One step further,
[`../httpbin-clack-one-source`](../httpbin-clack-one-source) has no
`worker.lisp` at all — its `build.sh` compiles
[`examples/net/httpbin-clack.lisp`](../../net/httpbin-clack.lisp), the file that
serves those endpoints on a real HTTP server, as the Worker unchanged on
`:server :rontolisp`.

## Limitations

The Worker sandbox and the `--no-wasi` build, exactly as in
[`../httpbin`](../httpbin/README.md#limitations): no standard input, no
filesystem, no `rontolisp:fetch` (use JavaScript's `fetch()` in
`src/index.js`). Printing does not trap — it is discarded — `random` works on a
generator `src/index.js` seeds from `crypto`, and the clock reads whatever
`src/index.js` hands to `__ronto_set_time` (per request here, so it advances
between requests and holds still inside one). And a
runtime `(ql:quickload ...)` cannot work: the one at the top of `worker.lisp` is
resolved at **compile** time and inlined into the module, which is why the first
`./build.sh` needs network and later ones do not.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello-clack/build.sh
```
