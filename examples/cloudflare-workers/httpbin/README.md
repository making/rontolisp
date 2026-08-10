# httpbin — a mini httpbin on Cloudflare Workers, without clack

The five echo endpoints of [httpbin.org](https://httpbin.org) as a
[Clack](https://github.com/fukamachi/clack) application
([`worker.lisp`](worker.lisp)), compiled to WebAssembly and served from a Worker
— with the adapter that puts it there **written out by hand** instead of
installed by `clack:clackup`.

```bash
./build.sh          # worker.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"method":"GET","headers":{"host":"localhost:8787",...},"path":"/get","args":{"b":"two","a":"1"}}

$ curl -X POST -d '{"name":"rontolisp"}' http://localhost:8787/post
{"data":"{\"name\":\"rontolisp\"}","args":{},"json":{"name":"rontolisp"},"method":"POST",...}
```

## The endpoints

`GET /get` echoes `args`, `headers`, `method` and `path`; `POST /post`,
`PUT /put`, `PATCH /patch` and `DELETE /delete` add `data` (the raw body) and
`json` (its parsed value). A wrong method answers **405** with the one it
wanted, an unknown path **404**, and a body that does not parse leaves
`"json": null` — the real httpbin's behaviour, which
[`net/httpbin.lisp`](../../net/httpbin.lisp) predates.

```bash
curl -X POST -d '{not json'  http://localhost:8787/post   # "json":null
curl         http://localhost:8787/post                   # 405 {"allowed":"POST",...}
curl         http://localhost:8787/nope                   # 404
```

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | **The whole program**: the Clack application (verbatim from upstream) plus the reactor adapter |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight — the local edit/run loop, and what the examples manifest runs |
| [`src/index.js`](src/index.js) | The whole Worker: `Request` -> JSON -> Lisp -> JSON -> `Response`, string boundary included |
| `src/worker.wasm` | A build product — run `./build.sh` first |

## Neither half of `worker.lisp` was invented here

Everything from `read-body` down to `app` is
[`net/httpbin-clack.lisp`](../../net/httpbin-clack.lisp) **verbatim**: an
ordinary Clack application, environment plist in, `(status headers body)` out,
no Cloudflare anywhere. The same function runs on hunchentoot, on woo, under
`wasmtime serve` and on the JVM unchanged.

Below the dashes is the **reactor adapter** — what `clack:clackup :server
:reactor` would have installed, written out in about thirty lines. It converts
nothing itself either: rontolisp's server protocol *is* Clack's, so there is one
implementation of it
([`http-server.lisp`](../../../src/main/resources/am/ik/rontolisp/eval/http-server.lisp)),
and the adapter calls the two entry points the JDK server, the WASI component
and both Clack handler backends meet in:

```lisp
(rontolisp::%http-make-env raw)          ; positional raw tuple -> the Clack environment
(rontolisp::%http-normalize-response r)  ; whatever app returned -> (status header-alist body-string)
```

The percent-decoding, the `?` split, the header lowercasing, the `Host` split,
the `content-length` parsing, the `:raw-body` stream and the response normalizer
therefore come for free and **cannot drift from what a served request sees**.
All that is left to write is the JSON envelope.

## How it relates to the other httpbins

| | this | [`../httpbin-clack`](../httpbin-clack) | [`net/httpbin.lisp`](../../net/httpbin.lisp) |
| --- | --- | --- | --- |
| The application | a Clack application | **the same file**, verbatim | a `rontolisp:http-handler` handler |
| How it is installed | thirty hand-written lines | `clack:clackup :server :reactor` | `(rontolisp:http-handler 'handle 8080)`, blocking on a socket |
| clack in the module | **none** | what the tree-shaker keeps of clack and lack | none |

This directory and `../httpbin-clack` ship *the same application* and answer
*the same JSON*; `src/index.js` is byte-identical between them. Only who builds
the Clack environment differs — and what that is worth in bytes is in the
[size report](../../../size-report/results/cloudflare-workers.md). Copy
`../httpbin-clack` when the program should look like every other Clack program;
copy this one when the module size matters more.

## How it works

### The interface is one exported function

A WASI command has no interface but stdout, and a Worker hands over a request
JavaScript has already parsed rather than a socket — so there is no server to
run. `worker.lisp` declares a host-callable export instead:

```lisp
(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)
```

As in [`../hello`](../hello) a `:string` crosses as UTF-8 bytes in linear
memory, but here one crosses *in*, which brings the allocator into the picture:
the module exports `memory`, `__ronto_alloc`, the arena pair
`__ronto_alloc_mark`/`__ronto_alloc_reset`, and
`handle-request(ptr, len) -> [ptr, len]`. The same boundary driven from Node and
Java is the subject of [`count-vowels/`](../../count-vowels).

`../httpbin-clack` declares no export at all: `wasm-export` needs a **literal**
name at compile time, so the compiler synthesizes one from a marker the handler
backend leaves behind. Writing the adapter by hand is what makes the export
ordinary again.

### The envelope, and two fields the JavaScript side must get right

The request and response are JSON because the other side is JavaScript. Out:

```json
{ "method": "GET", "target": "/path?a=1", "headers": {"host": "..."},
  "body": "", "scheme": "https", "remote-addr": "203.0.113.7" }
```

back:

```json
{ "status": 200, "headers": [["content-type", "application/json"]], "body": "..." }
```

This is the envelope the built-in `clack-handler-reactor` backend speaks, which
is why `src/index.js` is byte-identical in both directories. Two fields are
load-bearing, and both fail quietly:

- **Pass the raw target** (`url.pathname + url.search`) as one string, not a
  pre-split path plus a query object. `%http-make-env` does the `?` split and
  the percent-decoding itself; send a pre-split path and the application gets a
  `:query-string` of `nil`.
- **Forward `content-length`.** `%http-make-env` reads it off the header table
  and `lack/request`-style body parsing returns nothing without it — while a
  chunked request carries none. `src/index.js` sets it from the bytes it just
  read rather than copying the incoming header.

On the way out, response `headers` are an **array of pairs, not an object**:
`%http-normalize-response` answers an alist in which a name may repeat (two
cookies, two `Set-Cookie` headers). An object would collapse the duplicates.

### Nothing to shim: `--no-wasi`

The Worker calls the export rather than running the module, and the handler does
no I/O, so `build.sh` compiles with `--no-wasi` and the module imports nothing:

```console
$ node -e 'const m = new WebAssembly.Module(require("fs").readFileSync("src/worker.wasm"));
           console.log(WebAssembly.Module.imports(m))'
[]
```

Instantiating is `new WebAssembly.Instance(module, {})` — synchronous, empty
import object, no WASI shim in the project. `--no-wasi` also makes the module a
*reactor*, so its top-level forms run under `_initialize` instead of `_start`;
`src/index.js` calls it once when the isolate instantiates.

### Two heaps: wasm-GC collects one of them, you collect the other

The single thing most worth understanding before writing a handler of your own.
A rontolisp module has **two** memories:

| | The Lisp heap | Linear memory |
| --- | --- | --- |
| What lives there | every cons cell, hash table, instance and Lisp string, the Clack environment and the reply included | *only* the bytes of a string crossing the boundary, plus static data |
| Managed by | **the engine** — it is wasm-GC, and nothing in `src/index.js` touches it | **you.** The engine never traces it, so nothing there is freed on its own |
| Grows with | nothing, over time | every argument you write in, forever, unless you reclaim it |

The boundary is in the second column because **WebAssembly has no string type**.
A `:string` can only cross as UTF-8 bytes at a (pointer, length), and those
bytes are outside the GC's world by construction.

So the Lisp side of a request costs nothing to clean up and the *envelope* costs
a bracket: `__ronto_alloc` is a bump pointer with no `free`, and
`__ronto_alloc_mark`/`__ronto_alloc_reset` snapshot and restore its top. That
matters because the instance is **resident** — one isolate serves many requests.
Measured over 44,000 requests from Node: with the bracket, linear memory stays
at 262,144 B; without it, it grows without bound.

Two rules come with a manual arena, both observed in `handleRequest`: read the
returned bytes out **before** resetting, and only reset to a mark taken before
everything still live. Resetting is otherwise safe even when the call interned
new symbols — the floor is the interned-symbol pool's high-water mark. The
bracket is also why `handleRequest` is deliberately **synchronous**: an isolate
interleaves requests only at `await` points, so a call with no `await` cannot
have another request's allocation land inside the bracket.

[`../hello`](../hello) is the same boundary with the arena absent — nothing
crosses *into* that module. [`../httpbin-component`](../httpbin-component)
replaces this whole section with the canonical ABI, at
[a different set of costs](../README.md#would-the-component-model-be-simpler).

### Errors stay in the Lisp

Workers' engine supports the WebAssembly exception-handling proposal with no
flag, and rontolisp compiles `handler-case` into it automatically (under
wasmtime the same module needs `-W exceptions=y`). Two places use it: `body-json`
falls back to `null` when the body does not parse, and `handle-request` wraps the
whole adapter so any other Lisp error answers 500 and the instance keeps
serving. That is not optional on a reactor — an uncaught Lisp error is a **trap**,
which takes the instance down.

Exception handling is nearly free here: stripping both `handler-case` forms
shrinks the build by under 1%, because the spliced HTTP machinery compiles in EH
mode for its own stream forms regardless and the optimizer keeps only the
condition machinery the program reaches. `src/index.js` still catches whatever
escapes — that would be a real trap — and drops the instance, since a trapped
instance's Lisp heap cannot be trusted afterwards.

## Developing the handler without Cloudflare

`handle-request` is an ordinary function of a string, adapter included, so the
whole loop can happen on the interpreter — and identically on the other
backends, which is what keeps the handler honest:

```bash
rontolisp check.lisp
rontolisp check.lisp -o Check.class && java -cp . Check
rontolisp check.lisp -o check.wasm --optimize && wasmtime run -W gc -W exceptions=y check.wasm
```

Expect one difference when comparing those outputs: the **order of keys inside a
JSON object differs between backends**, because it follows hash-table iteration
order. The values are identical, and JSON objects are unordered — but a
byte-for-byte diff will show it.

## What it costs

The head-to-head against `../httpbin-clack` — the same application and requests
with `clackup` instead of the hand-written adapter — is
[measured there](../httpbin-clack/README.md#what-it-costs). The short version:
**the per-request cost is the same to the noise floor**, and what clack costs on
a reactor is module size and a little startup, paid once per isolate.

Being a Clack application is not free either: the module carries
`http-server.lisp` — the environment builder, the response normalizer, the
buffered `:raw-body` Gray stream — which a handler taking a pre-parsed JSON hash
table would not need. Measured against exactly that shape, the portable protocol
moved a warm `POST` from 0.038 ms to 0.048 ms, because the body arrives as a
stream `read-body` drains with `read-char` rather than as a string field. That
is the price of the application being portable.

`--optimize=size` is not optional here: a Worker bundle has a
[size limit](https://developers.cloudflare.com/workers/platform/limits/), and
the tree-shaker is what keeps the module small for no behaviour difference. The
`=size` level declines two speed-over-size emissions on top, which costs about
0.006 ms on a GET.

## Limitations

All of these are the Worker sandbox or the `--no-wasi` build, not rontolisp:

- **No standard input.** A reactor has no environment and no files, so
  `uiop:getenv` and `probe-file` answer nothing; `read-line` traps, because it
  is not true that input ended. Pass what the handler needs through the envelope.
- **The clock is the one JavaScript sets**, through the exported
  `__ronto_set_time` — before `_initialize` so a library timestamping at load
  sees it, and again per request so it advances. It does not tick *inside* a
  request (neither does a Worker's `Date.now()`), so `(sleep n)` signals. Until a
  host sets it, the clock built-ins signal a catchable error rather than
  reporting 1970.
- **`random` works; `rontolisp:random-bytes` does not.** The module carries its
  own generator, seeded from `crypto.getRandomValues` through
  `__ronto_seed_random`. That is unpredictable per isolate but not
  cryptographically strong, so the API promising entropy keeps signalling — mint
  tokens with `crypto.randomUUID()` and pass them in. Compiling with
  `--host-random` draws every number from the isolate instead, at the price of
  one import.
- **Printing is discarded, not trapped.** `print` and `format t` reach a sink
  under `--no-wasi`. Log from `src/index.js` with `console.log`, which reaches
  `npx wrangler tail`.
- **No filesystem.** `with-open-file` and `open` compile to call-time error
  stubs and a runtime `load` cannot work. A compile-time `(load "...")` is fine
  — it is inlined before the module reaches Cloudflare.
- **No outgoing HTTP from the Lisp.** `rontolisp:fetch` needs a WASI HTTP host;
  call JavaScript's `fetch()` in `src/index.js` and pass the result in.
- **Repeated query parameters collapse.** `args` is built with
  `alist-hash-table`, so `?a=1&a=2` reports only `"a":"1"`. Nothing is lost on
  the way in — `rontolisp:query-params` answers both pairs.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/httpbin/build.sh
```
