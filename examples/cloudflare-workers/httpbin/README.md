# httpbin — a mini httpbin on Cloudflare Workers, without clack

The five echo endpoints of [httpbin.org](https://httpbin.org), written as a
[Clack](https://github.com/fukamachi/clack) application
([`app.lisp`](app.lisp)), compiled to WebAssembly and served from a Worker — with
the adapter that puts it there **written out by hand** instead of installed by
`clack:clackup`.

```bash
./build.sh          # app.lisp -> src/app.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"method":"GET","headers":{"host":"localhost:8787","user-agent":"curl/8.7.1",...},"path":"/get","args":{"b":"two","a":"1"}}

$ curl -X POST -d '{"name":"rontolisp"}' http://localhost:8787/post
{"data":"{\"name\":\"rontolisp\"}","args":{},"json":{"name":"rontolisp"},"method":"POST",...}
```

## The endpoints

| | |
| --- | --- |
| `GET /get` | echo the request: `args`, `headers`, `method`, `path` |
| `POST /post` | the same, plus `data` (the raw body) and `json` (its parsed value) |
| `PUT /put`, `PATCH /patch`, `DELETE /delete` | ditto |

A wrong method answers **405** with the one it wanted, an unknown path **404**,
and a body that does not parse leaves `"json": null` — the real httpbin's
behaviour, which [`../../net/httpbin.lisp`](../../net/httpbin.lisp) explicitly
could not manage. See [Errors stay in the Lisp](#errors-stay-in-the-lisp).

```bash
curl -X POST -d '{not json'  http://localhost:8787/post   # "json":null
curl         http://localhost:8787/post                   # 405 {"allowed":"POST",...}
curl         http://localhost:8787/nope                   # 404
```

## The application is not from here, and neither is the adapter's logic

[`app.lisp`](app.lisp) is two halves with a line of dashes between them, and
neither half is code this directory invented.

Everything from `read-body` down to `app` is
[`../httpbin-clack/worker.lisp`](../httpbin-clack/worker.lisp) — that is,
[`examples/net/httpbin-clack.lisp`](../../net/httpbin-clack.lisp) — **verbatim**.
It is an ordinary Clack application: the environment plist in, the
`(status headers body)` list out, no Cloudflare anywhere in it. The same
function runs on hunchentoot, on woo, under `wasmtime serve` and on the JVM,
unchanged — and `rontolisp ../../net/httpbin-clack.lisp` serves this exact text
on a real socket.

```console
$ diff <(sed -n '/^;; --- request helpers/,/^;; --- the reactor adapter/p' app.lisp | sed '$d') \
       <(sed -n '/^;; --- request helpers/,$p' ../httpbin-clack/worker.lisp | sed '/^(ql:quickload/,$d')
$                                          # no output: identical
```

Below the dashes is the **reactor adapter**: what
`clack:clackup :server :cloudflare-workers` would have installed, written out in
about thirty lines. It converts nothing itself either. rontolisp's own server
protocol *is* Clack's, so there is exactly one implementation of it —
[`http-server.lisp`](../../../src/main/resources/am/ik/rontolisp/eval/http-server.lisp)
— and the adapter calls the same two entry points the JDK server, the WASI
component and both Clack handler backends meet in:

```lisp
(rontolisp::%http-make-env raw)          ; positional raw tuple -> the Clack environment
(rontolisp::%http-normalize-response r)  ; whatever app returned -> (status header-alist body-string)
```

The percent-decoding, the `?` split, the header lowercasing and comma-joining,
the `Host` split, the `content-length` parsing, the `:raw-body` stream and the
whole response normalizer therefore come for free, and **cannot drift from what a
served request sees**. All that is left to write is the JSON envelope.

## How it relates to the other httpbins

| | this | [`../httpbin-clack`](../httpbin-clack) | [`../../net/httpbin.lisp`](../../net/httpbin.lisp) |
| --- | --- | --- | --- |
| The application | a Clack application | **the same file**, verbatim | a `rontolisp:http-handler` handler (also the Clack shapes) |
| How it is installed | thirty hand-written lines | `clack:clackup :server :cloudflare-workers` | `(rontolisp:http-handler 'handle 8080)`, blocking on a socket |
| clack in the module | **none** | what the tree-shaker keeps of clack and lack | none |
| Module | **195 KB** (57 KB gzip) | 463 KB (122 KB gzip) | n/a, it is a server |

So this directory and `../httpbin-clack` ship *the same application* and answer
*the same JSON*; `src/index.js` is byte-identical between them. What differs is
only who builds the Clack environment — and that is worth **2.4× the module**
raw, 2.1× compressed, which is what this directory exists to show. (It used to
be 4×, until the `--optimize` funcall-dispatch gate learned to stay closed
across clack's handler discovery and to drop its dead file-loader — the clack
build shrank by more than half.) Copy `../httpbin-clack` when you want the
program to look like every other Clack program; copy this one when the module
size matters more than that.

## What's in here

| File | Purpose |
| --- | --- |
| [`app.lisp`](app.lisp) | **The whole program.** The Clack application (verbatim from upstream) plus the reactor adapter. |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight — the local edit/run loop, and what the examples manifest runs. |
| [`src/index.js`](src/index.js) | The whole Worker: `Request` -> JSON -> Lisp -> JSON -> `Response`, including the string boundary. |
| `src/app.wasm` | The compiled module (~195 KB). A build product — run `./build.sh` first. |

## How it works

### The interface is one exported function

A WASI command has no interface but stdout, which makes for an awkward request
handler. A Worker also hands over a request JavaScript has already parsed rather
than a socket, so there is no server to run either. `app.lisp` declares a
**host-callable export** instead:

```lisp
(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)
```

and the Worker calls that function directly. As in [`../hello`](../hello), a
`:string` crosses as UTF-8 bytes in linear memory — but here one crosses *in*,
which is what brings the allocator into the picture. The module exports
`memory`, the bump allocator `__ronto_alloc`, the arena pair
`__ronto_alloc_mark` / `__ronto_alloc_reset`, and
`handle-request(ptr, len) -> [ptr, len]`. `handleRequest` in
[`src/index.js`](src/index.js) is the dozen lines that do the bookkeeping; the
same boundary, driven from Node and from Java, is the subject of
[`examples/count-vowels/`](../../count-vowels).

`../httpbin-clack` declares no export at all: `clackup` cannot, because
`wasm-export` needs a **literal** name at compile time, so the compiler
synthesizes the export from a marker the handler backend leaves behind. Writing
the adapter by hand is what makes the export ordinary again.

### The envelope, and two fields the JavaScript side must get right

The request and response are **JSON** because the other side of the boundary is
JavaScript. What `src/index.js` sends (`scheme` and `remote-addr` are optional;
`method` defaults to `GET` and `target` to `/`):

```json
{ "method": "GET", "target": "/path?a=1", "headers": {"host": "..."},
  "body": "", "scheme": "https", "remote-addr": "203.0.113.7" }
```

and what it gets back:

```json
{ "status": 200, "headers": [["content-type", "application/json"]], "body": "..." }
```

This is the same envelope the built-in `clack-handler-cloudflare-workers` backend
speaks, which is why `src/index.js` is byte-identical in both directories. Two of
its fields are load-bearing, and both fail quietly rather than loudly:

- **Pass the raw target** (`url.pathname + url.search`) as one string, *not* a
  pre-split path plus a query object. `%http-make-env` does the `?` split and the
  percent-decoding itself, and `:path-info` / `:query-string` have to come from it
  for a Clack application to see what Clack promises. Send a pre-split path and
  the application gets a `:query-string` of `nil`.
- **Forward `content-length`.** `%http-make-env` reads `:content-length` off the
  header table, and `lack/request`-style body parsing returns *nothing* without
  it — while a request that arrived chunked carries no `content-length` at all.
  `src/index.js` therefore sets it from the bytes it just read rather than
  copying the incoming header.

And one thing to notice on the way out: the response `headers` are an **array of
pairs, not an object**, because `%http-normalize-response` answers an alist in
which a name may repeat — an application that sets two cookies answers two
`Set-Cookie` headers. `src/index.js` feeds the array straight to the `Headers`
constructor; an object would have collapsed the duplicates.

### Nothing to shim: `--no-wasi`

Because the Worker calls the export rather than running the module, and because
the handler does no I/O, `build.sh` compiles with `--no-wasi`. The module then
imports nothing whatsoever:

```console
$ node -e 'const m = new WebAssembly.Module(require("fs").readFileSync("src/app.wasm"));
           console.log(WebAssembly.Module.imports(m))'
[]
```

so instantiating it is `new WebAssembly.Instance(module, {})` — synchronous, with
an empty import object, no WASI shim anywhere in the project. (Without
`--no-wasi` the module would import `fd_write` and friends, and the Worker would
have to supply them; [`examples/browser/wasm-browser/wasi-shim.js`](../../browser/wasm-browser/wasi-shim.js)
is a small implementation to start from if you ever need one.)

`--no-wasi` also changes the entry point: a module with no WASI is a *reactor*,
not a command, so its top-level forms run under `_initialize` instead of
`_start`. `src/index.js` calls it once, when the isolate instantiates the module.

### Two heaps: wasm-GC collects one of them, you collect the other

This is the single thing most worth understanding before writing a handler of
your own, because it is easy to assume WebAssembly GC covers everything. It does
not. A rontolisp module has **two** memories:

| | The Lisp heap | Linear memory |
| --- | --- | --- |
| What lives there | every cons cell, hash table, CLOS instance and Lisp string `app.lisp` builds — including the Clack environment and the reply it renders | *only* the bytes of a string crossing the boundary, plus the compiler's own static data |
| Managed by | **the engine.** It is wasm-GC: objects become unreachable and V8 reclaims them. Nothing in `src/index.js` touches it. | **you.** The engine never traces it, so nothing in there is ever freed on its own. |
| Grows with | nothing, over time | every argument you write in, forever, unless you reclaim it |

The reason the boundary is in the second column is that **WebAssembly has no
string type**. A `:string` parameter can only cross as UTF-8 bytes at a
(pointer, length) in linear memory, and `:string` results come back the same way.
Those bytes are outside the GC's world by construction — the wasm-GC proposal
gives the engine no way to trace a flat byte array the host wrote into.

So the Lisp side of a request costs you nothing to clean up, and the *envelope*
costs you a bracket. `__ronto_alloc` is a bump pointer with no `free`;
`__ronto_alloc_mark` / `__ronto_alloc_reset` snapshot and restore its top, which
reclaims the argument *and* whatever the call itself put in linear memory.

That only matters because the instance is **resident**: importing a `.wasm` file
in a Worker yields a compiled `WebAssembly.Module` (Cloudflare compiles it at
deploy time, so no request pays for compilation), instantiating is per-isolate
work, and `src/index.js` therefore does it once and caches it. One isolate serves
many requests. Measured over 44 000 requests, driving this same `src/app.wasm`
from Node:

| | linear memory after 44 000 requests |
| --- | --- |
| with the mark/reset bracket | 262 144 bytes (unchanged) |
| without it | grows without bound |

Two rules come with a manual arena, both observed in `handleRequest`: read the
returned bytes out **before** resetting (the result lives in the scratch the
reset hands back), and only ever reset to a mark taken **before** everything
still live. Resetting is otherwise safe even when the call interned new symbols —
the reset floor is the interned-symbol pool's high-water mark, not the raw
snapshot.

The bracket is also why `handleRequest` is deliberately **synchronous**. A Worker
isolate interleaves concurrent requests only at `await` points, so a call with no
`await` inside it cannot have another request's allocation land in the middle of
the bracket.

[`../hello`](../hello) is the same boundary with the arena absent: nothing
crosses *into* that module, so there is nothing to reclaim and no bracket to
write. The allocator arrives with the first string argument, not with wasm-GC.

[`../httpbin-component`](../httpbin-component) is this same `app.lisp` with that
whole section replaced by the canonical ABI — and with a different set of costs;
the [directory README](../README.md#would-the-component-model-be-simpler)
compares them.

The Lisp side keeps its own globals across requests within an isolate — that is
ordinary Cloudflare behaviour, and it is per isolate, not global. Anything that
must actually persist belongs in KV, D1 or a Durable Object, reached from
`src/index.js`.

### Errors stay in the Lisp

Workers' engine supports the WebAssembly exception-handling proposal with no
compatibility flag and no `wrangler.jsonc` setting, and rontolisp compiles
`handler-case` into it automatically. (Under wasmtime the same module needs
`-W exceptions=y`; V8 needs no equivalent.) Two places use it:

- `body-json` falls back to `null` when the body does not parse, exactly like the
  real httpbin. `net/httpbin.lisp` says in its header that it cannot do this —
  it predates catching on the WASM backends.
- `handle-request` wraps the whole adapter, so any other Lisp error answers 500
  and the instance keeps serving. That is not optional on a reactor: an uncaught
  Lisp error is a **trap**, which takes the instance down and forces the host to
  throw it away. The `clack-handler-cloudflare-workers` backend catches in exactly
  the same place, for exactly the same reason.

Exception handling is not free: it pulls the condition system into the module and
roughly doubles its size here. `src/index.js` still catches whatever escapes —
that would be a real WASM trap — and drops the instance, since a trapped
instance's Lisp heap cannot be trusted afterwards.

## Developing the handler without Cloudflare

`handle-request` is an ordinary function of a string — the adapter as much as the
application — so the whole edit/run loop can happen on the interpreter:

```bash
rontolisp check.lisp
```

It runs identically on the JVM and the WASM backend, which is what keeps the
handler honest:

```bash
rontolisp check.lisp -o Check.class && java -cp . Check
rontolisp check.lisp -o check.wasm --optimize && wasmtime run -W gc -W exceptions=y check.wasm
```

One thing to expect when comparing those outputs: the **order of the keys inside
a JSON object differs between backends**, because it follows hash-table iteration
order, which rontolisp does not fix across backends. The values are identical.
JSON objects are unordered, so nothing downstream cares — but a byte-for-byte
diff of two backends' output will show it.

## Size

`--optimize=size` is not optional here. A Worker bundle has a size limit (see
[Workers limits](https://developers.cloudflare.com/workers/platform/limits/)),
and the tree-shaker is what keeps this module at **200,155 B** instead of
**521,925 B** — it ships only the functions the program actually reaches, for no
behaviour difference — with the `=size` level declining the two
speed-over-size emissions on top (that part costs a few microseconds per
request, measured below against `--optimize`: +0.006 ms on a GET). Compressed
it is **58,793 B**, about 1.9% of the free plan's 3 MB.

Measured on node 24 (2026-08-08, the `=size` builds) driving
[`src/index.js`](src/index.js)'s boundary code against this exact
`src/app.wasm`, next to `../httpbin-clack`'s — the same application, the same
envelope, the same requests, `clackup` and clack instead of the hand-written
adapter:

| | this | [`../httpbin-clack`](../httpbin-clack) |
| --- | --- | --- |
| imports | **zero** | zero |
| module | **200,155 B** raw / **58,793 B** gzip | 474,150 B raw / 124,756 B gzip |
| `WebAssembly.Module` compile | 0.3 ms | 0.6 ms — and on Cloudflare *no request pays it*, the module is compiled at deploy time |
| `_initialize`, cold | **4.5 ms** | 12.5 ms — clack's entire load time, `clackup` included |
| warm `GET /get` | 0.024 ms | 0.024 ms |
| warm `POST /post` | 0.053 ms | 0.053 ms |
| linear memory after 44 000 requests | 262 144 B | 262 144 B |

**The per-request cost is the same to three decimal places.** What clack costs on
a reactor is module size and a little startup — and on Cloudflare startup is paid
once per isolate, not once per request, which is why `../httpbin-clack` is a
perfectly reasonable thing to deploy. This directory is the version to reach for
when the 2.4× module is the thing you cannot afford.

Being a Clack application is not free either: the module carries
`http-server.lisp` — the environment builder, the response normalizer and the
buffered `:raw-body` Gray stream — which a handler taking a pre-parsed JSON hash
table would not need. Measured against exactly that shape (this directory's
previous one, back when both builds were roughly twice their current size):
carrying the portable protocol cost about 140 KB and moved a warm `POST` from
0.038 ms to 0.048 ms, because the body arrives as a stream `read-body` drains
with `read-char` instead of as a string field. That is the price of the
application being portable.

## Limitations

Everything below is the Worker sandbox or the `--no-wasi` build, not rontolisp
itself:

- **No input, time or randomness in the Lisp.** `--no-wasi` means `random`,
  `get-universal-time` and `uiop:getenv` have nothing behind them and trap when
  called — a stub could only answer by inventing data. Return values instead.
- **Printing is discarded, not trapped.** `print` and `format t` reach a sink
  under `--no-wasi` (a reactor host hands the module no file descriptors), so
  they cost nothing and lose everything. Do the logging in `src/index.js` with
  `console.log` (which reaches `npx wrangler tail`). If you want the Lisp itself
  to print for real, drop `--no-wasi`, supply a WASI shim, and call `_start`
  instead of `_initialize`.
- **No filesystem.** Even with WASI shimmed, a Worker has no files:
  `with-open-file` and `open` compile to call-time error stubs under `--no-wasi`
  (a catchable, self-describing error rather than a trap), and a runtime `load`
  cannot work either. A compile-time `(load "...")` is fine — it is inlined into
  the module before it ever reaches Cloudflare.
- **No outgoing HTTP from the Lisp.** `rontolisp:fetch` needs a WASI HTTP host;
  a Worker has JavaScript's `fetch()` instead. Call it in `src/index.js` and pass
  the result into the handler.
- **Repeated query parameters collapse.** The echo document builds `args` with
  `alist-hash-table`, so `?a=1&a=2` reports only `"a":"1"`. The real httpbin
  returns a list. Nothing is lost on the way in — `:query-string` carries the
  whole thing, and `rontolisp:query-params` answers both pairs — so a handler that
  wants httpbin's behaviour can have it.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/httpbin/build.sh
```
