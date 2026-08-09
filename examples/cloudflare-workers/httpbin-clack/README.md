# httpbin-clack — a **Clack application** on Cloudflare Workers, one source for every host

The same five echo endpoints as [`../httpbin`](../httpbin) — in fact the same
[Clack](https://github.com/fukamachi/clack) application, byte for byte — put on
the Worker by `clack:clackup`. There is **no `worker.lisp` in this directory**,
and that is the point: the program is
[`examples/net/httpbin-clack.lisp`](../../net/httpbin-clack.lisp) *itself* —
the file that serves the same endpoints on the interpreter, on the JVM and
under `wasmtime serve` — compiled here, unchanged, for a host that calls an
export instead of handing over a socket. Same file, same `clackup` line, same
`:server :rontolisp`.

```bash
./build.sh          # ../../net/httpbin-clack.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"method":"GET","headers":{"host":"localhost:8787","user-agent":"curl/8.7.1",...},"path":"/get","args":{"b":"two","a":"1"}}

$ curl -X POST -d '{"name":"rontolisp"}' http://localhost:8787/post
{"data":"{\"name\":\"rontolisp\"}","args":{},"json":{"name":"rontolisp"},"method":"POST",...}
```

## One source, four hosts

`:server :rontolisp` means "serve on **this target's** native inbound
transport", and the transport is chosen at *compile* time:

| Build | Transport | How it runs |
| --- | --- | --- |
| interpret / `-o App.class` | the program binds a socket | `rontolisp ../../net/httpbin-clack.lisp`, then `curl :8080` |
| `-o app.wasm --component` | the host owns the socket (wasi:http) | `wasmtime serve ... app.wasm` |
| `-o worker.wasm --no-wasi` | the host **calls** the module — a reactor | this directory: `src/index.js` calls the `handle-request` export |

The `clackup` line does not change between the rows — `:port 8080` applies
where the program owns the socket and is ignored where the host does, and
`:use-thread nil` is what keeps the interpreter and the JVM serving in the
foreground. Deploying to Cloudflare is not a port of the program; it is a
compile flag.

The explicit `:server :reactor` designator still exists and still works — it
means "host-driven on *every* backend", stores the application even
where `:rontolisp` would bind a socket, and is what lets a Worker be driven
through `dispatch` on the interpreter with no Cloudflare in sight.
[`../hello-clack`](../hello-clack) demonstrates that shape (and its
`check.lisp` loop); this directory no longer needs it.

## What this answers that `../httpbin` does not

Both directories run the identical Clack application: it takes the Clack
environment plist and returns the Clack `(status headers body)` list, so the
**same function** also runs on hunchentoot and on woo, unchanged. What
`../httpbin` does instead of quickloading clack is write the reactor adapter
out by hand — thirty lines under a row of dashes, calling the same
`%http-make-env` / `%http-normalize-response` entry points [the handler backend
does](#it-converts-nothing) — so that clack is never in the module. Same
application, same envelope, same answers, and `src/index.js` is byte-identical
between the two directories.

|  | `../httpbin` | this |
| --- | --- | --- |
| The application | a Clack application | **the same file**, verbatim |
| How it reaches the Worker | thirty hand-written lines and an explicit `wasm-export` | `clackup`, with the export synthesized |
| Reads as | a Worker program that happens to speak Clack | **every other Clack program** |
| clack in the module | none | what the tree-shaker keeps of clack and lack |
| Module | 179 KB raw / **55 KB gzip** | 264 KB raw / **79 KB gzip** |

79 KB gzip is about **2.6%** of the free plan's 3 MB bundle limit, so it fits
with plenty of room — but it is **1.9×** the hand-written adapter compressed
(2.1× raw), and that is the honest trade: you are paying for clack and lack to
be in the module so that the file reads like an ordinary Clack program rather
than like a Worker. The [cost table below](#what-it-costs) shows where the rest
goes — module size, not per-request time.

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

## What's in here

| File | Purpose |
| --- | --- |
| [`../../net/httpbin-clack.lisp`](../../net/httpbin-clack.lisp) | **The whole program** — not in this directory, deliberately. `build.sh` compiles it. |
| [`build.sh`](build.sh) | `--no-wasi --optimize=size` over that file. |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../httpbin/src/index.js` — same envelope, same boundary code, same file. |
| `src/worker.wasm` | The compiled module (~264 KB). A build product — run `./build.sh` first. |

The directory used to carry the program as a `worker.lisp` of its own — first
split into an `app.lisp` and a transport, then as the upstream example verbatim
with a different `clackup` **tail** (`:server :reactor`). Each step deleted a
difference, and this is the last one: since `:server :rontolisp`
serves reactors too, the tail is the same, so the copy bought nothing but the
chance to drift. There is nothing left to `diff` — the deployed program and the
upstream example are one file.

## The backend is a handler backend, not example code

Nothing here writes an adapter. `clack-handler-rontolisp` is the **built-in
Clack handler backend** `clackup` resolves for `:server :rontolisp`, and under
`--no-wasi` it takes its reactor shape: the whole bridge from the host's JSON
envelope to the Clack environment and back.

### Where the export comes from

`clackup` applies the backend's `run`, and a reactor owns no socket, so `run`
stores the application and returns. What replaces the socket is a WASM export —
and `rontolisp:wasm-export` needs a **literal** name at compile time, which a
program whose whole Worker half is a `clackup` call cannot give it. So the
backend's `run` carries a marker and the compiler answers it, appending the
equivalent of

```lisp
(defun %reactor-dispatch (json) (rontolisp::%http-reactor-dispatch json))
(rontolisp:wasm-export '%reactor-dispatch :as "handle-request"
                       :params '(:string) :returns :string)
```

after the program. `%http-reactor-dispatch` runs the stored application over
the JSON envelope; it is the **shared** reactor machinery
([`http-reactor.lisp`](../../../src/main/resources/am/ik/rontolisp/eval/http-reactor.lisp)),
and the `:reactor` backend's `dispatch` is a thin public name over the very
same functions — the two designators cannot drift, and both store the
application in one place.

One keyword in the upstream file matters beyond sockets: **`:use-thread nil`**.
The WASM backends are single-threaded, so `clackup` already defaults to `nil`
there — but the interpreter and the JVM have threads, and serving in the
foreground is what a script wants. `clackup`'s default middlewares stay **on**:
lack's `backtrace` middleware prints its report to `*error-output*`, which
under `--no-wasi` is a sink (discarded, not a trap) and everywhere else is real
standard error.

### It converts nothing

The backend is thin because everything a transport has to do is already
factored out of the server, in
[`http-server.lisp`](../../../src/main/resources/am/ik/rontolisp/eval/http-server.lisp).
rontolisp's own server protocol *is* Clack's, so there is exactly one
implementation of it and every backend goes through the same two functions:

```lisp
(rontolisp::%http-make-env raw)        ; positional raw tuple -> the Clack environment
(rontolisp::%http-normalize-response r) ; whatever app returned -> (status header-alist body-string)
```

The JDK server, the WASI component, the socket legs of
`clack.handler.rontolisp` and the reactor machinery all meet there, so the
percent-decoding, the `?` split, the header lowercasing and comma-joining, the
`Host` split, the content-length parsing, the `:raw-body` stream and the whole
response normalizer come for free — and cannot drift from what a *served*
request sees. All that is left in the reactor is the JSON envelope below.

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

Measured on node 24 (V8, the same engine family as workerd, 2026-08-09) driving
the byte-identical boundary code of [`src/index.js`](src/index.js) against each
directory's `src/worker.wasm`, over the same requests:

| | `../httpbin` | this |
| --- | --- | --- |
| imports | zero | **zero** — the Worker instantiates with `{}`, no WASI shim |
| exports | `memory`, `_initialize`, `__ronto_alloc`, `__ronto_alloc_mark`, `__ronto_alloc_reset`, `handle-request` | **identical**, which is why one `src/index.js` serves both |
| module | 178,971 B raw / 54,648 B gzip | 264,277 B raw / **79,438 B gzip** |
| `WebAssembly.Module` compile | 0.3 ms | 0.8 ms — and on Cloudflare *no request pays it*, the module is compiled at deploy time |
| `_initialize`, cold | 4.5 ms | **5.0 ms** — clack's entire load time, `clackup` included |
| warm `GET /get` | 0.039 ms | **0.038 ms** |
| warm `POST /post` | 0.060 ms | **0.058 ms** |
| linear memory after 44,000 requests | 262,144 B | 262,144 B |

**The per-request rows are the same to the noise floor.** Everything clack
costs on a reactor is in the module-size row — and on Cloudflare startup is
paid once per isolate, not once per request.

`clackup` itself is a slice of that startup rather than of the request path:
measured when this directory switched from calling `handle` behind a
hand-written export to calling `clackup`, the module grew 1,575,467 →
1,691,678 B (**+9%**) and `_initialize` roughly doubled, while warm `GET` and
warm `POST` did not move at all. (Both absolute sizes predate the 2026-08-08
dispatch-gate refinement and the CLOS lowering that later halved this build;
the +9% ratio is what the paragraph records.)

Those per-request figures are the Lisp call plus the string boundary, with V8
warm; the first call of a fresh isolate is ~40 ms while V8 tiers the module up.

On the real edge, `wrangler deploy` reported **1654.60 KiB upload / 371.94 KiB
gzip** and a **Worker Startup Time of 26 ms** (14 ms before `clackup`) — measured
before the module shrank to today's 264 KB; the next deploy will report the
smaller bundle — and all five endpoints (plus the 405, the 404, the unparseable
body and a percent-encoded path) answer correctly there — verified after
deploying, not inferred. End-to-end
`curl` from this side of the Pacific settles at 50-85 ms, which is network time:
the Lisp share of it is the 0.05 ms above.

The linear-memory row is flat rather than climbing because of the
`__ronto_alloc_mark` / `__ronto_alloc_reset` bracket in `src/index.js`;
[`../httpbin/README.md`](../httpbin/README.md#two-heaps-wasm-gc-collects-one-of-them-you-collect-the-other)
explains why it is needed at all.

## Verify it against a real Clack server

The most direct check that this is a Clack application is to serve it as one —
with the *same file*, and no Cloudflare, no wrangler and no JavaScript in
sight:

```bash
rontolisp ../../net/httpbin-clack.lisp     # http://127.0.0.1:8080, Ctrl-C to stop
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
document. Nothing was recompiled between the two, and nothing was edited: it is
the same file, so there is no diff to keep honest.

It compiles the same way for the other two hosts, which is what
[`examples/examples.yaml`](../../examples.yaml) pins (a blocking server, so the
manifest builds it rather than running it):

```bash
rontolisp ../../net/httpbin-clack.lisp -o Serve.class && \
  java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Serve
rontolisp ../../net/httpbin-clack.lisp -o serve.wasm --component && \
  wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y serve.wasm
```

WASM Preview 1 is the one host where `clackup` cannot **serve**: it has no
incoming TCP, so the program compiles and `clackup` fails at run time. A Worker
is the other side of that coin — the host is the one doing the calling, and
`--no-wasi` is how this directory says so.

## Developing without Cloudflare

The edit/run loop for this Worker *is* the server loop above: run the file on
the interpreter, `curl` it, edit, run again — nothing about the reactor build
changes the application's behavior, and the four-host section explains why.

When you want to drive the reactor *shape* itself as an ordinary function — a
JSON request string in, a JSON response string out, on any backend — that is
what the explicit `:server :reactor` designator is for: its `run` stores the
application everywhere, and `clack.handler.reactor:dispatch` is exactly what
the synthesized export calls. [`../hello-clack/check.lisp`](../hello-clack/check.lisp) and
[`../httpbin-tiny-routes/check.lisp`](../httpbin-tiny-routes/check.lisp) are
that loop, pinned by the examples manifest on the interpreter, the JVM and
WASM.

As in `../httpbin`, the **order of the keys inside a JSON object differs between
backends** — it follows hash-table iteration order. The values are identical.

## Middleware, `lack:builder`, sessions

`clackup`'s default `backtrace` middleware is in this module and active — its
report goes to `*error-output*`, a sink under `--no-wasi` — so "middleware
works" is not hypothetical. What is still out of reach is the *session/body*
stack: **`(ql:quickload "lack-request")` in a `--no-wasi` program traps at
`_initialize` today**. Measured, and narrowed to one line upstream:

```
lack-request -> http-body -> fast-http -> smart-buffer
```

and `smart-buffer/src/smart-buffer.lisp` names its temporary directory with a
**top-level `(random ...)`**. `--no-wasi` has no WASI, so `random` compiles to an
`unreachable` stub — which is the documented `--no-wasi` limitation
([below](#limitations)) firing at *load* time instead of at call time, where a
`handler-case` could at least see it. This is the same shape as clackup's own
`format t` calls, and it is deliberately **not** fixed the same way: output has
a meaningful null destination, so it goes to a sink, while a `random` that
answered zeros would hand the program data it cannot tell from real. The same program is fine on the
interpreter, on the JVM and as a Preview 1 `_start` module: only the reactor
build, whose top-level forms run inside `_initialize`, hits it.

The Lisp side is otherwise ready for it: the reactor hands over a genuine Clack
environment, `content-length` included, which is exactly what
`lack/request:request-parameters` needs, and the application neither knows nor
cares what is wrapped around it. When the load-time trap is gone, adding the
middleware stack is an edit to the one source and nothing else.

## Why `clackup` prints, and why that is fine here

`clackup` writes two lines unconditionally — its own startup banner, and
`clack.handler:run`'s debug NOTICE — and they are in upstream clack, not here.
On a `--no-wasi` module there is no stdout to write them to. They used to
**trap** the instance at `_initialize` for exactly that reason, which is why
this example could not call `clackup` at all; now standard output and standard
error are a **sink** under `--no-wasi`, so the bytes are simply discarded.

That is a deliberate policy rather than a patch for clack: a reactor host hands
the module no file descriptors, so discarding loses only the bytes, while the
alternative was killing the instance for a log line — and it applies to every
library that logs while it loads, not just this one. Input, time and `random`
still trap under `--no-wasi`, because a stub can only answer those by inventing
data.

Locally (`rontolisp ../../net/httpbin-clack.lisp`, `wasmtime`) the two lines
are visible on real stdout. Pass `:silent t :debug nil` if you would rather not
see them.

## Limitations

Everything below is the Worker sandbox or the `--no-wasi` build, and every one
of them applies to [`../httpbin`](../httpbin/README.md#limitations) identically:
no input, time or `random` in the Lisp (they trap; `print` and `format t` do
not, but their output is discarded), no filesystem (`with-open-file` and `open`
signal a catchable error), no `rontolisp:fetch` (use JavaScript's `fetch()` in
`src/index.js`).
One more is specific to this directory: a runtime `(ql:quickload ...)` cannot
work either — the `ql:quickload` form in the source is resolved at
**compile** time and inlined into the module, which is why the first
`./build.sh` needs network and later ones do not.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/httpbin-clack/build.sh
```
