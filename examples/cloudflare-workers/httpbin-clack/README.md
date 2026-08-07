# httpbin-clack — a **Clack application** on Cloudflare Workers

The same five echo endpoints as [`../httpbin`](../httpbin), written as a
[Clack](https://github.com/fukamachi/clack) application instead of a Worker
handler. It is the Cloudflare port of
[`examples/net/httpbin-clack.lisp`](../../net/httpbin-clack.lisp), and the port
consists of deleting one form and adding four.

```bash
./build.sh          # worker.lisp + app.lisp -> src/app.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"method":"GET","headers":{"host":"localhost:8787","user-agent":"curl/8.7.1",...},"path":"/get","args":{"b":"two","a":"1"}}

$ curl -X POST -d '{"name":"rontolisp"}' http://localhost:8787/post
{"data":"{\"name\":\"rontolisp\"}","args":{},"json":{"name":"rontolisp"},"method":"POST",...}
```

## What this answers that `../httpbin` does not

`../httpbin`'s handler is a *Worker* handler: it takes a JSON hash table this
repository invented and returns a JSON envelope. That is a fine way to write a
Worker, and it is the smaller module — but the handler only runs on a Worker.

Here, `app` is a Clack application: it takes the Clack environment plist and
returns the Clack `(status headers body)` list. So the **same function** also
runs on hunchentoot, on woo, under `wasmtime serve`, and on the JVM, unchanged.
[`app.lisp`](app.lisp) *is*
[`../../net/httpbin-clack.lisp`](../../net/httpbin-clack.lisp) with one form
removed and nothing added — that file's last form is

```lisp
(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
```

and **`clackup` is the only thing this Worker replaces** — in the separate
[`worker.lisp`](worker.lisp), which is the only file that knows Cloudflare
exists, and which is four forms long because the adapter is a built-in Clack
handler backend rather than example code. A Worker hands you a parsed request
rather than a socket, so there is no server to run.

|  | `../httpbin` | this |
| --- | --- | --- |
| `app` is | a Worker handler (JSON in, JSON envelope out) | a **Clack application** (environment in, Clack response out) |
| Portable to another Clack handler | no, it would have to be rewritten | **yes, unchanged** |
| Query string | pre-split by JavaScript's `URLSearchParams` | split and percent-decoded by Clack's own env builder, so `:path-info` / `:query-string` are what Clack promises |
| Body | a string field | Clack's `:raw-body` — a synchronous bivalent stream, drained with `read-char` |
| Module | 283 KB raw / **91 KB gzip** | 1.57 MB raw / **334 KB gzip** |

334 KB gzip is about **11%** of the free plan's 3 MB bundle limit, so it fits
with room — but it is **3.7×** the hand-rolled Worker, and that is the honest
trade: you are paying for the whole of clack and lack to be in the module so that
the application can be an ordinary Clack application. If the program will only
ever run on a Worker, `../httpbin` is the cheaper shape.

## The endpoints

| | |
| --- | --- |
| `GET /get` | echo the request: `args`, `headers`, `method`, `path` |
| `POST /post` | the same, plus `data` (the raw body) and `json` (its parsed value) |
| `PUT /put`, `PATCH /patch`, `DELETE /delete` | ditto |

A wrong method answers **405** with the one it wanted, an unknown path **404**,
and a body that does not parse leaves `"json": null`.

```bash
curl -X POST -d '{not json'  http://localhost:8787/post   # "json":null
curl         http://localhost:8787/post                   # 405 {"allowed":"POST",...}
curl         http://localhost:8787/nope                   # 404
```

There is no `GET /` index page here, unlike `../httpbin`: the Clack application
is carried over verbatim, and `net/httpbin-clack.lisp` has none.

## What's in here

| File | Purpose |
| --- | --- |
| [`app.lisp`](app.lisp) | **The Clack application, and nothing else** — `net/httpbin-clack.lisp` with its `clackup` line removed, with nothing added. No Cloudflare in it anywhere. |
| [`worker.lisp`](worker.lisp) | The other half, and it is **four forms**: quickload the handler backend, load the application, export one function, call `handle` from it. This is what `build.sh` compiles. |
| [`serve.lisp`](serve.lisp) | `worker.lisp`'s counterpart: `(load "app.lisp")` plus the `clackup` call — the very form `worker.lisp` replaces. Serves the same application over real HTTP. |
| [`demo.lisp`](demo.lisp) | Drives the Worker's handler with no Cloudflare in sight — the local edit/run loop. |
| [`src/index.js`](src/index.js) | The whole Worker. The boundary code is `../httpbin/src/index.js` unchanged; only `requestToJson` differs. |
| `src/app.wasm` | The compiled module (~1.57 MB). A build product — run `./build.sh` first. |

`worker.lisp` and `serve.lisp` are **peers over one application**, and that is
the point of the split:

```
app.lisp  +  worker.lisp   ->  a Cloudflare Worker (one exported function)
app.lisp  +  serve.lisp    ->  an HTTP server on port 8080
```

It turns "the application is portable" from a claim in a comment into two things
you can check. By `diff` — `app.lisp` is the upstream example minus one form:

```console
$ diff <(sed -n '/^(ql:quickload/,/^;; --- the Clack/p' ../../net/httpbin-clack.lisp) \
       <(sed -n '/^(ql:quickload/,/^;; --- the Clack/p' app.lisp)
$                                          # no output: identical
```

and by *running* it — see [the next section](#verify-it-against-a-real-clack-server).

## The adapter is a handler backend, not example code

`worker.lisp` writes no adapter. `clack-handler-cloudflare` is a **built-in Clack
handler backend** — the sibling of `clack-handler-rontolisp`, which is what
`clackup` uses when it *does* own a socket — and it is the whole bridge:

```lisp
(ql:quickload "clack-handler-cloudflare")
(load "app.lisp")

(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)

(defun handle-request (request-json)
  (clack.handler.cloudflare:handle #'app request-json))
```

That is `worker.lisp` in full. `handle` is `(app request-json) -> response-json`:
it builds the Clack environment, runs the application, lowers the Clack response
back, and answers 500 rather than trapping if the application signals.

It is a handler backend rather than a `rontolisp:`-level function on purpose.
Nothing in the envelope is actually Cloudflare-specific — the same `handle` works
from a browser page, from node and from a JVM host — but a name chosen for the
mechanism would not be findable by the people who need it, and a vendor name in
`rontolisp:` would be worse. A handler backend is where the Clack ecosystem
already puts per-host names (`clack-handler-hunchentoot`, `clack-handler-woo`).

`(clack:clackup #'app :server :cloudflare)` resolves the same backend and fails
with a sentence: a reactor owns no socket, so there is nothing for `run` to
start. See [below](#why-not-clackclackup-itself).

The backend is also usable **without clack at all** — a Clack application is
just a function of the environment, and nothing here needs the library to be
present. That build is the one to compare against when reading the size table
below: a program that is only `clack-handler-cloudflare` plus a four-line
application compiles to **357,765 B raw / 116,932 B gzip**. So the 1.57 MB this
directory ships is not the adapter — it is clack and lack, which `app.lisp`
quickloads so that it stays byte-identical to the upstream example.

### It converts nothing

The backend is thin because everything a transport has to do is already factored
out of the server, in
[`http-server.lisp`](../../../src/main/resources/am/ik/rontolisp/eval/http-server.lisp).
rontolisp's own server protocol *is* Clack's, so there is exactly one
implementation of it and every backend goes through the same two functions:

```lisp
(rontolisp::%http-make-env raw)        ; positional raw tuple -> the Clack environment
(rontolisp::%http-normalize-response r) ; whatever app returned -> (status header-alist body-string)
```

The JDK server, the WASI component, `clack.handler.rontolisp` and
`clack.handler.cloudflare` all meet there, so the percent-decoding, the `?`
split, the header lowercasing and comma-joining, the `Host` split, the
content-length parsing, the `:raw-body` stream and the whole response normalizer
come for free — and cannot drift from what a *served* request sees. All that is
left in the backend is the JSON envelope below.

### The envelope, and two things the JavaScript side must get right

What `src/index.js` sends (`scheme` and `remote-addr` are optional; `method`
defaults to `GET` and `target` to `/`):

```json
{ "method": "GET", "target": "/path?a=1", "headers": {"host": "..."},
  "body": "", "scheme": "https", "remote-addr": "203.0.113.7" }
```

and what it gets back:

```json
{ "status": 200, "headers": [["content-type", "text/plain"]], "body": "..." }
```

Both of these were found by measurement, and both fail quietly rather than
loudly:

- **Pass the raw target** (`url.pathname + url.search`) as one string, *not* the
  pre-split `path` + `query` object `../httpbin`'s Worker sends. `%http-make-env`
  does the `?` split and the percent-decoding itself, and `:path-info` /
  `:query-string` have to come from it for a Clack application to see what Clack
  promises. Send a pre-split path and a Clack app gets a `:query-string` of
  `nil`.
- **Forward `content-length`.** `%http-make-env` reads `:content-length` off the
  header table, and `lack/request`'s body parsing returns *nothing* without it —
  the first version of this probe silently produced empty parameters. A request
  that arrived chunked carries no `content-length` at all, so `src/index.js` sets
  it from the bytes it just read rather than copying the incoming header.

And one thing to notice on the way out: the response `headers` are an **array of
pairs, not an object**, because `%http-normalize-response` answers an alist in
which a name may repeat — a Clack application that sets two cookies answers two
`Set-Cookie` headers. `src/index.js` feeds the array straight to the `Headers`
constructor; an object would have collapsed the duplicates.

## What it costs

Measured on node 24 (V8, the same engine family as workerd) driving the
byte-for-byte boundary code of [`src/index.js`](src/index.js) against this exact
`src/app.wasm`:

| | `../httpbin` | this |
| --- | --- | --- |
| imports | **zero** | **zero** — the Worker instantiates with `{}`, no WASI shim |
| exports | `memory`, `_initialize`, `__ronto_alloc`, `__ronto_alloc_mark`, `__ronto_alloc_reset`, `handle-request` | **identical**, which is why `src/index.js` could be reused as is |
| module | 283,200 B raw / 91,743 B gzip | 1,575,331 B raw / **342,700 B gzip** |
| `WebAssembly.Module` compile | 1.7 ms | 6.5 ms — and on Cloudflare *no request pays it*, the module is compiled at deploy time |
| `_initialize` | 18.7 ms | 24.4 ms — clack's entire load time is the difference |
| warm `GET /get` | | **0.08 ms** |
| warm `POST /post` | | **0.13 ms** |

Those per-request figures are the Lisp call plus the string boundary, with V8
warm; the first call of a fresh isolate is ~40 ms while V8 tiers the module up.

On the real edge, `wrangler deploy` reports **1540.98 KiB upload / 340.29 KiB
gzip** and a **Worker Startup Time of 17 ms**, and all five endpoints (plus the
405, the 404, the unparseable body, a percent-encoded path and a UTF-8 query)
answer correctly there — verified after deploying, not inferred. End-to-end
`curl` from this side of the Pacific settles at 50-85 ms, which is network time:
the Lisp share of it is the 0.08 ms above.

Linear memory sat at 327,680 bytes after 14,000 requests — the
`__ronto_alloc_mark` / `__ronto_alloc_reset` bracket in `src/index.js` is what
keeps it flat, and [`../httpbin/README.md`](../httpbin/README.md#two-heaps-wasm-gc-collects-one-of-them-you-collect-the-other)
explains why it is needed at all.

## Verify it against a real Clack server

The most direct check that this is a Clack application is to serve it as one.
[`serve.lisp`](serve.lisp) is `(load "app.lisp")` plus the `clackup` call — the
one form `worker.lisp` replaces — so it needs no Cloudflare, no wrangler and no
JavaScript at all:

```bash
rontolisp serve.lisp                       # http://127.0.0.1:8080, Ctrl-C to stop
```

```console
$ curl 'http://127.0.0.1:8080/get?a=1&b=two'
{"args":{"a":"1","b":"two"},"headers":{"accept":"*/*","host":"127.0.0.1:8080","user-agent":"curl/8.5.0"},"method":"GET","path":"/get"}

$ curl -X POST -H 'content-type: application/json' -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post
{"args":{},"headers":{...,"content-type":"application/json","content-length":"20"},"method":"POST","path":"/post","data":"{\"name\":\"rontolisp\"}","json":{"name":"rontolisp"}}

$ curl -X POST -d '{not json' http://127.0.0.1:8080/post   # "json":null
$ curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/post   # 405
$ curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/nope   # 404
```

Point the same curls at `npx wrangler dev` and the answers are the same
document. Nothing was recompiled between the two — `app.lisp` did not change.

It compiles the same way, too, which is what
[`examples/examples.yaml`](../../examples.yaml) pins (a blocking server, so the
manifest builds it rather than running it):

```bash
rontolisp serve.lisp -o Serve.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Serve
rontolisp serve.lisp -o serve.wasm --component && \
  wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y serve.wasm
```

WASM Preview 1 is the one host where `clackup` cannot serve: it has no incoming
TCP, so the program compiles and `clackup` fails at run time. That is also why
the Worker needs `worker.lisp` at all — see
[below](#why-not-clackclackup-itself).

## Developing without Cloudflare

`handle-request` is an ordinary function of a string, so the Worker's adapter —
not just the application — can be developed on the interpreter:

```bash
rontolisp demo.lisp
```

and it runs identically on the JVM and the WASM backend, which
`examples/examples.yaml` pins as well:

```bash
rontolisp demo.lisp -o Prog.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Prog
rontolisp demo.lisp -o demo.wasm --optimize && wasmtime run -W gc -W exceptions=y demo.wasm
```

The JVM run needs the compiler jar on the classpath, unlike `../httpbin`'s:
quickloading clack splices the `clack-handler-rontolisp` handler backend, whose
`run` reaches the JVM server seam, so the compiled class carries the injected
HTTP runtime even though nothing here ever starts a server.

As in `../httpbin`, the **order of the keys inside a JSON object differs between
backends** — it follows hash-table iteration order. The values are identical.

## Middleware, `lack:builder`, sessions

Nothing here uses `lack/request` or the middleware stack — and that is not only a
scoping choice. **`(ql:quickload "lack-request")` in a `--no-wasi` program traps
at `_initialize` today**, so the middleware variant of this example cannot be
built yet. Measured, and narrowed to one line upstream:

```
lack-request -> http-body -> fast-http -> smart-buffer
```

and `smart-buffer/src/smart-buffer.lisp` names its temporary directory with a
**top-level `(random ...)`**. `--no-wasi` has no WASI, so `random` compiles to an
`unreachable` stub — which is the documented `--no-wasi` limitation
([below](#limitations)) firing at *load* time instead of at call time, where a
`handler-case` could at least see it. The same program is fine on the
interpreter, on the JVM and as a Preview 1 `_start` module: only the reactor
build, whose top-level forms run inside `_initialize`, hits it.

The Lisp side is otherwise ready for it: the adapter hands over a genuine Clack
environment, `content-length` included, which is exactly what
`lack/request:request-parameters` needs, and `worker.lisp` neither knows nor
cares what is wrapped around `app`. When the load-time trap is gone, adding the
middleware stack is an `app.lisp` change and nothing else.

## Why not `clack:clackup` itself?

Because `clackup` on a `--no-wasi` reactor traps at instantiation today: the
handler backend's WASM leg delegates to the `rontolisp:http-handler` directive,
and on Preview 1 — which `--no-wasi` output belongs to — that directive is a
call-time error, because Preview 1 has no incoming TCP. Making `clackup` work on
a host that calls an exported function instead of handing over a socket is a
separate, larger piece of work (it needs the compiler to synthesize the export,
the way the component path already synthesizes its serve bridge); until it
lands, `clack.handler.cloudflare:handle` is what a Worker calls, and `run` exists
only so that `:server :cloudflare` fails with that sentence rather than with
"undefined function". The application itself is unaffected either way, which is
the whole point of keeping it a Clack application.

## Limitations

Everything below is the Worker sandbox or the `--no-wasi` build, and every one
of them applies to [`../httpbin`](../httpbin/README.md#limitations) identically:
no I/O at all in the Lisp (`print`, `random`, `get-universal-time` trap), no
filesystem, no `rontolisp:fetch` (use JavaScript's `fetch()` in `src/index.js`).
One more is specific to this directory: a runtime `(ql:quickload ...)` cannot
work either — the `(ql:quickload "clack")` at the top of `app.lisp`, like the
`(load "app.lisp")` at the top of `worker.lisp`, is resolved at **compile** time
and inlined into the module, which is why the first `./build.sh` needs network
and later ones do not.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/httpbin-clack/build.sh
```
