# httpbin — a mini httpbin on Cloudflare Workers, with no library

The five echo endpoints of [httpbin.org](https://httpbin.org)
([`worker.lisp`](worker.lisp)), compiled to WebAssembly and served from a
Worker — with the adapter that puts it there **written out by hand** instead of
installed by a library, so nothing but the program ships.

```bash
./build.sh          # worker.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"args":{"a":"1","b":"two"},"headers":{"host":"localhost:8787",...},"method":"GET","path":"/get"}

$ curl -X POST -d '{"name":"rontolisp"}' http://localhost:8787/post
{"args":{},...,"data":"{\"name\":\"rontolisp\"}","json":{"name":"rontolisp"}}
```

## The endpoints

`GET /get` echoes `args`, `headers`, `method` and `path`; `POST /post`,
`PUT /put`, `PATCH /patch` and `DELETE /delete` add `data` (the raw body) and
`json` (its parsed value). A wrong method answers **405** with the one it
wanted, an unknown path **404**, and a body that does not parse leaves
`"json": null` — the real httpbin's behaviour.

```bash
curl -X POST -d '{not json'  http://localhost:8787/post   # "json":null
curl         http://localhost:8787/post                   # 405 {"allowed":"POST",...}
curl         http://localhost:8787/nope                   # 404
```

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | **The whole program**: the endpoints plus the reactor adapter |
| [`check.lisp`](check.lisp) | Drives it with no Cloudflare in sight — the local edit/run loop |
| [`src/index.js`](src/index.js) | The whole Worker: `Request` -> JSON -> Lisp -> JSON -> `Response` |
| `src/worker.wasm` | A build product — run `./build.sh` first |

## The interface is one exported function

A Worker hands over a request JavaScript has already parsed rather than a
socket, so there is no server to run. `worker.lisp` declares a host-callable
export instead:

```lisp
(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)
```

As in [`../hello`](../hello) a `:string` crosses as UTF-8 bytes in linear
memory, but here one crosses *in*, which brings the allocator into the picture:
the module exports `memory`, `__ronto_alloc`, the arena pair
`__ronto_alloc_mark`/`__ronto_alloc_reset`, and
`handle-request(ptr, len) -> [ptr, len]`.

The adapter under the endpoints is what `clack:clackup :server :reactor` would
have installed, in about thirty lines — and it converts nothing itself.
rontolisp's server protocol *is* Clack's, so there is one implementation of it
([`http-server.lisp`](../../../src/main/resources/am/ik/rontolisp/eval/http-server.lisp)),
and the adapter calls the two entry points every transport meets in:

```lisp
(rontolisp::%http-make-env raw)          ; positional raw tuple -> the Clack environment
(rontolisp::%http-normalize-response r)  ; whatever the handler returned -> (status header-alist body-string)
```

The percent-decoding, the `?` split, the header lowercasing, the `Host` split,
the `content-length` parsing, the `:raw-body` stream and the response normalizer
therefore come for free and **cannot drift from what a served request sees**.
All that is left to write is the JSON envelope. Hand the same job to a library
instead and you get [`../httpbin-clack`](../httpbin-clack), which declares no
export at all: `wasm-export` needs a **literal** name at compile time, so the
compiler synthesizes one from a marker the handler backend leaves behind.

## The envelope, and two fields the JavaScript side must get right

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
is why `src/index.js` is byte-identical across these directories. Two fields are
load-bearing, and both fail quietly:

- **Pass the raw target** (`url.pathname + url.search`) as one string, not a
  pre-split path plus a query object. `%http-make-env` does the `?` split and
  the percent-decoding itself; send a pre-split path and the application gets a
  `:query-string` of `nil`.
- **Forward `content-length`.** `%http-make-env` reads it off the header table
  and body parsing returns nothing without it — while a chunked request carries
  none. `src/index.js` sets it from the bytes it just read rather than copying
  the incoming header.

On the way out, response `headers` are an **array of pairs, not an object**:
`%http-normalize-response` answers an alist in which a name may repeat (two
cookies, two `Set-Cookie` headers). An object would collapse the duplicates.

## Nothing to shim: `--no-wasi`

The Worker calls the export rather than running the module, and the handler does
no I/O, so `build.sh` compiles with `--no-wasi` and the module imports nothing:

```console
$ node -e 'const m = new WebAssembly.Module(require("fs").readFileSync("src/worker.wasm"));
           console.log(WebAssembly.Module.imports(m))'
[]
```

Instantiating is `new WebAssembly.Instance(module, {})` — synchronous, empty
import object, no WASI shim in the project. `--no-wasi` also makes the module a
*reactor*, so its top-level forms run under `_initialize` instead of `_start`.

`--optimize=size` is not optional either: a Worker bundle has a
[size limit](https://developers.cloudflare.com/workers/platform/limits/), and
the tree-shaker is what keeps the module small for no behaviour difference.

## Two heaps

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
flat; without it, it grows without bound.

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

## Errors stay in the Lisp

Workers' engine supports the WebAssembly exception-handling proposal with no
flag, and rontolisp compiles `handler-case` into it automatically (under
wasmtime the same module needs `-W exceptions=y`). Two places use it: `body-json`
falls back to `null` when the body does not parse, and `handle-request` wraps the
whole adapter so any other Lisp error answers 500 and the instance keeps
serving. That is not optional on a reactor — an uncaught Lisp error is a **trap**,
which takes the instance down. `src/index.js` still catches whatever escapes —
that would be a real trap — and drops the instance, since a trapped instance's
Lisp heap cannot be trusted afterwards.

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
order. The values are identical.

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
