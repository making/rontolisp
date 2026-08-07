# httpbin — a mini httpbin on Cloudflare Workers

The five echo endpoints of [httpbin.org](https://httpbin.org), in Common Lisp
([`app.lisp`](app.lisp)), compiled to WebAssembly and served from a Worker. It is
the Cloudflare port of [`examples/net/httpbin.lisp`](../../net/httpbin.lisp) —
same endpoints, same JSON, a different way in.

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
| `GET /` | a plain-text index |
| `GET /get` | echo the request: `args`, `headers`, `method`, `path` |
| `POST /post` | the same, plus `data` (the raw body) and `json` (its parsed value) |
| `PUT /put`, `PATCH /patch`, `DELETE /delete` | ditto |

A wrong method answers **405** with the one it wanted, an unknown path **404**,
and a body that does not parse leaves `"json": null` — the real httpbin's
behaviour, which `../../net/httpbin.lisp` explicitly could not manage. See
[Errors stay in the Lisp](#errors-stay-in-the-lisp).

```bash
curl -X POST -d '{not json'  http://localhost:8787/post   # "json":null
curl         http://localhost:8787/post                   # 405 {"allowed":"POST",...}
curl         http://localhost:8787/nope                   # 404
```

## What's in here

| File | Purpose |
| --- | --- |
| [`app.lisp`](app.lisp) | The handler. One exported function: JSON request string -> JSON response string. |
| [`demo.lisp`](demo.lisp) | Drives the same handler with no Cloudflare in sight — the local edit/run loop. |
| [`src/index.js`](src/index.js) | The whole Worker: `Request` -> JSON -> Lisp -> JSON -> `Response`, including the string boundary. |
| `src/app.wasm` | The compiled module (~277 KB). A build product — run `./build.sh` first. |

## How it differs from `net/httpbin.lisp`

That one is a *server*: `(rontolisp:http-handler 'handle 8080)`, a handler taking
the Clack environment and returning a Clack response, blocking on a socket. A
Worker provides none of that — no WASI, no sockets, no server loop — so the same
five endpoints arrive here through a different door.

| | `net/httpbin.lisp` | this |
| --- | --- | --- |
| Entry point | `rontolisp:http-handler` binds a port | one `rontolisp:wasm-export`ed function |
| Request | the Clack environment plist | a JSON string, parsed to a hash table |
| Query string | `rontolisp:query-params` on `:query-string` | already split by JavaScript's `URLSearchParams` |
| Body | `(await (read-all (getf env :raw-body)))` | already read, as a string |
| Response | the Clack `(status headers body)` list | a JSON envelope the Worker rebuilds |

The routing and the echo document itself are the same code either way.

## How it works

### The interface is one exported function

A WASI command has no interface but stdout, which makes for an awkward request
handler. So `app.lisp` does not run as a whole program per request. It declares a
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

The request and response are **JSON** because the other side of the boundary is
JavaScript. `handle-request` takes what `src/index.js` built out of the incoming
`Request`:

```json
{ "method": "GET", "url": "...", "path": "/get",
  "query": {"a": "1"}, "headers": {...}, "body": "" }
```

and returns what becomes the `Response`:

```json
{ "status": 200, "headers": {"content-type": "application/json"}, "body": "..." }
```

`rontolisp:json-parse` and `rontolisp:json-stringify` handle both directions in
the Lisp; `rontolisp:plist-hash-table` is what makes building the echo document
readable. `args` and `headers` need no conversion at all — they arrive as JSON
objects, which are already the string-keyed hash tables `json-stringify` writes
back out as objects.

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
| What lives there | every cons cell, hash table, CLOS instance and Lisp string `app.lisp` builds — including the parsed request and the reply it renders | *only* the bytes of a string crossing the boundary, plus the compiler's own static data |
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
many requests. Measured over 20 000 `POST /post` calls, driving this same
`src/app.wasm` from Node:

| | linear memory after 20 000 requests |
| --- | --- |
| with the mark/reset bracket | 262 144 bytes (unchanged) |
| without it | 2 162 688 bytes and climbing |

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
- `handle-request` wraps the router, so any other Lisp error answers 500 and the
  instance keeps serving.

Exception handling is not free: it pulls the condition system into the module and
roughly doubles its size here. `src/index.js` still catches whatever escapes —
that would be a real WASM trap — and drops the instance, since a trapped
instance's Lisp heap cannot be trusted afterwards.

## Developing the handler without Cloudflare

`handle-request` is an ordinary function of a string, so the whole edit/run loop
can happen on the interpreter:

```bash
rontolisp demo.lisp
```

It runs identically on the JVM and the WASM backend, which is what keeps the
handler honest:

```bash
rontolisp demo.lisp -o Demo.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Demo
rontolisp demo.lisp -o demo.wasm --optimize && wasmtime run -W gc -W exceptions=y demo.wasm
```

One thing to expect when comparing those outputs: the **order of the keys inside
a JSON object differs between backends**, because it follows hash-table iteration
order, which rontolisp does not fix across backends. The values are identical.
JSON objects are unordered, so nothing downstream cares — but a byte-for-byte
diff of two backends' output will show it.

## Size

`--optimize` is not optional here. A Worker bundle has a size limit (see
[Workers limits](https://developers.cloudflare.com/workers/platform/limits/)),
and the tree-shaker is what keeps this module at **277 KB** instead of **580 KB**
— it ships only the functions the program actually reaches, for no behaviour
difference.

The limit applies to the *compressed* bundle, and `wrangler deploy` reports both:
this Worker uploads as `278.53 KiB / gzip: 91.03 KiB`, so about 3% of the free
plan's 3 MB.

## Limitations

Everything below is the Worker sandbox or the `--no-wasi` build, not rontolisp
itself:

- **No I/O at all in the Lisp.** `--no-wasi` means `print`, `format t`, `random`,
  `get-universal-time` and `uiop:getenv` have nothing behind them and trap when
  called. Return values instead, and do the logging in `src/index.js` with
  `console.log` (which reaches `npx wrangler tail`). If you want the Lisp itself
  to print, drop `--no-wasi`, supply a WASI shim, and call `_start` instead of
  `_initialize`.
- **No filesystem.** Even with WASI shimmed, a Worker has no files:
  `with-open-file` and a runtime `load` cannot work. A compile-time
  `(load "...")` is fine — it is inlined into the module before it ever reaches
  Cloudflare.
- **No outgoing HTTP from the Lisp.** `rontolisp:fetch` needs a WASI HTTP host;
  a Worker has JavaScript's `fetch()` instead. Call it in `src/index.js` and pass
  the result into the handler.
- **Repeated query parameters collapse.** `src/index.js` builds `args` with
  `Object.fromEntries(url.searchParams)`, so `?a=1&a=2` keeps only the last. The
  real httpbin returns a list.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/httpbin/build.sh
```
