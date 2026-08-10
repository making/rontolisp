# hello-clack — a Clack application on Cloudflare Workers

The smallest thing that is still a real
[Clack](https://github.com/fukamachi/clack) application: `ql:quickload`, one
`defun`, `clack:clackup`.

```bash
./build.sh          # worker.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl http://localhost:8787/
Hello from Clack on Cloudflare Workers!
GET /
```

## The whole program

```lisp
(ql:quickload '("clack" "clack-handler-reactor"))

(defun app (env)
  (list 200 '(:content-type "text/plain; charset=utf-8")
        (list
         (format nil "Hello from Clack on Cloudflare Workers!~%~a ~a~%"
                 (getf env :request-method) (getf env :path-info)))))

(clack:clackup #'app :server :reactor :use-thread nil)
```

That is the whole of Clack's API: an application is a **function** of the
environment plist returning the `(status headers body)` list, and a middleware
is a function from application to application ([`../httpbin-clack`](../httpbin-clack)
has one). There is no Worker-specific code here, so `app` runs on hunchentoot,
on woo, under `wasmtime serve` and on the JVM, unchanged.

`:reactor` is a built-in handler backend that is **host-driven on every
backend**: its `run` stores the application and returns, and what replaces the
socket is one WASM export, `handle-request` (a JSON request string in, a JSON
response string out). You do not declare it: `rontolisp:wasm-export` needs a
literal name at compile time, which a `clackup` call has none to give, so the
compiler synthesizes it from a marker the backend leaves behind. The
[Clack guide](../../../doc/en/guides/clack.md) has the full story.

A Worker does not strictly need this designator — `:server :rontolisp` picks
each target's own transport, which is how
[`../httpbin-clack-one-source`](../httpbin-clack-one-source) deploys a socket
server unchanged. What `:reactor` adds is "host-driven *everywhere*", which is
what lets [`check.lisp`](check.lisp) drive the whole Worker on the interpreter.

**`:use-thread nil`** is a property of the other backends rather than
boilerplate: it is already the default on WASM, but the interpreter and the JVM
have threads and `clackup` would otherwise store the application on one of them,
racing the next form.

## Developing without Cloudflare

`clack.handler.reactor:dispatch` is an ordinary function of a JSON string, and
exactly what the synthesized export calls, so the whole Worker runs on every
backend — which [`examples/examples.yaml`](../../examples.yaml) pins:

```bash
rontolisp check.lisp
rontolisp check.lisp -o Check.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Check
rontolisp check.lisp -o check.wasm && wasmtime run -W gc -W exceptions=y check.wasm
```

The two lines before the first `-->` are upstream clack's own banner and debug
notice. On a Worker (`--no-wasi`) standard output is a **sink**, so those bytes
are discarded rather than trapping the instance; locally they are not. Pass
`:silent t :debug nil` to quiet them.

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | The whole program — three forms. This is what `build.sh` compiles. |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight, on any backend. |
| [`src/index.js`](src/index.js) | The whole Worker: `Request` -> JSON -> Lisp -> JSON -> `Response`. |
| `src/worker.wasm` | A build product — run `./build.sh` first. |

The cost of "it is a real Clack application" is module size
([size report](../../../size-report/results/cloudflare-workers.md)) and isolate
startup, both paid once; the per-request cost is the Lisp call plus the string
boundary. [`../hello`](../hello) is the cheaper shape if a program will only
ever run on a Worker.

## Limitations

The Worker sandbox and the `--no-wasi` build, exactly as in
[`../httpbin`](../httpbin/README.md#limitations): no standard input, no
filesystem, no `rontolisp:fetch` (use JavaScript's `fetch()` in
`src/index.js`). Printing is discarded rather than trapping, `random` works on a
generator `src/index.js` seeds from `crypto`, and the clock is whatever
`src/index.js` hands to `__ronto_set_time`. A *runtime* `(ql:quickload ...)`
cannot work: the one at the top of `worker.lisp` is resolved at **compile** time
and inlined, which is why the first `./build.sh` needs network and later ones do
not.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello-clack/build.sh
```
