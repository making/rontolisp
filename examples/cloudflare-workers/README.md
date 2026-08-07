# rontolisp on Cloudflare Workers

Five ways to run a Common Lisp program, compiled to WebAssembly by rontolisp,
on Cloudflare Workers. Each directory is a complete, independent Worker project:
`./build.sh && npx wrangler dev`, then `npx wrangler deploy`.

| Directory | What it is | Module | Host glue |
| --- | --- | --- | --- |
| [`hello/`](hello) | **Start here.** Three Lisp functions the Worker calls like JavaScript functions: `add`, `fib`, and a string-returning `greet`. | **563 B**, `--no-gc`, plain MVP module, zero imports | 32 lines, no dependencies |
| [`hello-clack/`](hello-clack) | **Start here if you want Clack.** The smallest real [Clack](https://github.com/fukamachi/clack) application: `ql:quickload`, one `defun`, and `clack:clackup :server :cloudflare-workers`. No Worker-specific code in the Lisp at all — the compiler synthesizes the exported entry point. | 1.65 MB (**351 KB gzip**), `--no-wasi` wasm-GC, zero imports | 45 lines, one file, no dependencies |
| [`httpbin/`](httpbin) | A **mini httpbin**: `/get`, `/post`, `/put`, `/patch`, `/delete` echoing the request as JSON, 405 and 404, and `handler-case`. The Cloudflare port of [`examples/net/httpbin.lisp`](../net/httpbin.lisp). | 277 KB, `--no-wasi` wasm-GC, zero imports | 53 lines, one file, no dependencies |
| [`httpbin-clack/`](httpbin-clack) | **The same endpoints as a real [Clack](https://github.com/fukamachi/clack) application** — the environment plist in, the Clack response list out — so the handler also runs on hunchentoot, on woo and under `wasmtime serve`, unchanged. The Cloudflare port of [`examples/net/httpbin-clack.lisp`](../net/httpbin-clack.lisp): `app.lisp` is that file minus its `clackup` line and nothing else, `worker.lisp` is three forms — quickload, load, `clackup` again, this time with `:server :cloudflare-workers`, the built-in handler backend whose export the compiler synthesizes — and `serve.lisp` puts the `clackup` line back so the identical application can be curl'd on a real HTTP server. | 1.69 MB (**366 KB gzip**), `--no-wasi` wasm-GC, zero imports | `httpbin/`'s boundary code verbatim; only the request shape differs |
| [`httpbin-component/`](httpbin-component) | **The same `httpbin` Lisp source**, reached through the component model (`--component` + `jco transpile`) instead of raw linear memory. | 3 core modules (278 KB) | 51 hand-written lines + 247 KB of generated glue |

## Which one should I copy?

`hello/` if your Lisp is numbers and strings — it is by far the smallest thing
that works, and it needs no runtime support at all.

`httpbin/` for anything else. It is the shortest path to the full language on
Workers, and it is a straight port of an existing rontolisp server example, so
the two are worth diffing.

`hello-clack/` to see what a Clack application on a Worker looks like with
nothing else in the way — three forms, and the `:server` designator is the only
thing that mentions Cloudflare. It is `httpbin-clack/` with the endpoints,
the body handling and the second `clackup` target removed.

`httpbin-clack/` when the program must **also** run somewhere that is not a
Worker. It is the same five endpoints, but the handler is a Clack application
rather than a Worker handler, so it ports to any Clack server unchanged — for
3.7× the compressed module, because the whole of clack and lack ships inside it.
That directory states the trade in full.

`httpbin-component/` answers a question rather than being a recommendation:
*wouldn't the component model be simpler?* For the string marshalling, yes — see
below. Everywhere else, no.

## What is settled about Workers

All of this was verified end to end under `npx wrangler dev` (workerd), not
inferred:

- **wasm-GC runs.** rontolisp's default output is WebAssembly GC (a cons cell is
  a GC struct), and workerd is V8, which has had WasmGC on by default since
  Chrome 119. No compatibility flag, no `wrangler.jsonc` setting.
- **wasm exception handling runs.** `handler-case` compiles into the exception
  proposal, and it works with no flag — so a Lisp error can answer 500 from
  inside the Lisp, and `httpbin`'s unparseable-body case can answer `"json":
  null` the way the real httpbin does. Under wasmtime the same module
  needs `-W exceptions=y`; V8 needs no equivalent.
- **A Worker may not compile WebAssembly at run time.** `import module from
  "./x.wasm"` gives an already-compiled `WebAssembly.Module` — Cloudflare
  compiles it at deploy time, so no request pays for it. Anything that calls
  `WebAssembly.compile()` on bytes instead will hang at startup; that is the one
  fact that shapes the `httpbin-component/` build.
- **An instance is per isolate, not per request.** Instantiate once, cache it,
  and remember that Lisp globals then persist between requests of that isolate.
  Anything that must really persist belongs in KV, D1 or a Durable Object.
- **wasm-GC does not cover the boundary.** Inside the module, wasm-GC is real:
  cons cells, hash tables and Lisp strings are engine-managed objects and no
  host code goes near them. But WebAssembly has no string type, so a string
  crossing the boundary can only be UTF-8 bytes in **linear memory**, which the
  engine never traces. Those bytes are the host's to reclaim — which is what the
  `__ronto_alloc_mark`/`_reset` bracket in `httpbin/src/index.js` is, and why
  `hello/` (nothing crosses *in*) needs no such code at all. Details:
  [Two heaps](httpbin/README.md#two-heaps-wasm-gc-collects-one-of-them-you-collect-the-other).

## Would the component model be simpler?

Only in one place, and it is a real one. Compare the call itself:

```js
// httpbin: linear memory, by hand (src/index.js)
const mark = exports.__ronto_alloc_mark();
const ptr = exports.__ronto_alloc(bytes.length);
new Uint8Array(exports.memory.buffer, ptr, bytes.length).set(bytes);
const [resultPtr, resultLen] = exports["handle-request"](ptr, bytes.length);
const result = decoder.decode(new Uint8Array(exports.memory.buffer.slice(resultPtr, resultPtr + resultLen)));
exports.__ronto_alloc_reset(mark);

// component: the canonical ABI does all of that
const result = lisp.handleRequest(input);
```

That is the whole benefit. The costs, all measured on the identical `app.lisp`:

| | `httpbin` (`--no-wasi`) | `httpbin-component` (`--component` + jco) |
| --- | --- | --- |
| Files the Worker imports | 1 `.wasm` | 3 `.wasm` + 247 KB generated `.js` |
| Build tools | the rontolisp compiler | + `@bytecodealliance/jco` |
| WASI imports to satisfy | none | 3 interfaces, stubbed by hand |
| Top-level forms (`defparameter`) | run via `_initialize` | **cannot run** — see below |
| Hand-written glue | 53 lines, boundary included | 50 lines, stubs included |

Three findings behind that table, none of them obvious:

1. **jco's default output does not run on Workers.** It calls
   `WebAssembly.compile()` on a base64 blob at module scope, and workerd rejects
   the module with `Top-level await in module is unsettled`. With `--tla-compat`
   the Worker starts but every request hangs instead. The mode that works is
   `--instantiation sync`, where the glue asks *the host* for each compiled core
   module — which is exactly a `.wasm` import.
2. **`handler-case` needs `--bindgen-enable-wasm-exnref`.** Without it jco
   refuses the component outright: `exceptions proposal not enabled`.
3. **A component's top-level forms cannot be run through jco.** They live in the
   component's `wasi:cli/run` export, and calling it fails with `task exited
   without resolution` (jco cannot drive a stackful-async export). `app.lisp`
   therefore keeps its state inside functions rather than in a `defparameter` —
   which costs nothing here, but is a hard constraint on that path. The
   `--no-wasi` build has no such problem: `_initialize` is an ordinary call.

A wasm-GC component also imports `wasi:cli/stdout`, `wasi:cli/types` and
`wasi:filesystem/types` whether or not the program does any I/O, because it is a
`wasi:cli/run` command by construction. Those are the three stubs.

`hello/` is the case where the component model is genuinely clean: because the
program fits the `--no-gc` subset the component imports nothing at all, and
`jco transpile --instantiation sync -b 0` produces glue with no dependencies.
Even there it is 92 KB of generated JavaScript to replace about ten lines.

## Developing without Cloudflare

The Lisp in every one of these is an ordinary function, so the whole edit/run
loop happens locally — [`httpbin/demo.lisp`](httpbin/demo.lisp) and
[`httpbin-clack/demo.lisp`](httpbin-clack/demo.lisp) drive their handlers on the
interpreter, the JVM and wasmtime, and `httpbin-clack/`'s application can also be
served for real with `rontolisp httpbin-clack/serve.lisp` — no Cloudflare, no
JavaScript. Every Lisp source here is pinned by `examples/examples.yaml`; while
iterating on them, narrow the suite:

```bash
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false \
       -Drontolisp.examples=true -Drontolisp.examples.only=cloudflare test
# Tests run: 10, instead of 217 in ~6min
```

## Deploying

All four were deployed to the real edge and verified there, not only under
`wrangler dev`:

| | Upload | gzip | Worker Startup Time |
| --- | --- | --- | --- |
| `hello` | 1.88 KiB | **1.12 KiB** | not reported |
| `httpbin` | 278.53 KiB | **91.03 KiB** | 12-13 ms |
| `httpbin-component` | 503.84 KiB | **130.21 KiB** | 5 ms |
| `hello-clack` | 1608.77 KiB | **358.27 KiB** | 30 ms |
| `httpbin-clack` | 1654.60 KiB | **372.11 KiB** | 25 ms |

The gzip column is the one that counts: the Worker size limit applies to the
compressed bundle, so even the component build sits at about 4% of the free
plan's 3 MB, and `httpbin-clack` — the largest of the five by a wide margin,
because the whole of clack and lack is inside it — at about 12%. Its startup
time is also the one that moved when it went through `clack:clackup` instead of
calling the handler backend directly (14 → 25 ms); the per-request cost did not.

"Worker Startup Time" is what `wrangler deploy` measures and budget-checks, and
it is why `httpbin/src/index.js` instantiates at **module scope** rather than
from the first request. Both work — deferring it was measured and serves
requests fine — but at module scope the cost is paid once per isolate outside
the request path and Cloudflare validates it at deploy time.

**One gotcha, and it is not your code**: for several minutes after a Worker's
*first* deploy, its freshly created `*.workers.dev` hostname answers
intermittently with Cloudflare edge errors — `error code: 1042`, `1104`, or a
bare 404 — while the route propagates across PoPs. The giveaway is that
`wrangler tail` shows nothing at all for those requests: they never reach the
Worker. It settles on its own (measured: 2/30 failures ten minutes in, 0/30 four
minutes later). Re-deploys of an existing Worker do not show it.

## Building

Every directory's `build.sh` needs the compiler jar, built once from the
repository root:

```bash
./mvnw clean package -DskipTests
```

The `.wasm` files are build products and are not checked in.
