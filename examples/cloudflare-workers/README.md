# rontolisp on Cloudflare Workers

Ways to run a Common Lisp program, compiled to WebAssembly by rontolisp, on
Cloudflare Workers. Each directory is a complete, independent Worker project:
`./build.sh && npx wrangler dev`, then `npx wrangler deploy`.

| Directory | What it is | Module | Host glue |
| --- | --- | --- | --- |
| [`hello/`](hello) | **Start here.** Three Lisp functions the Worker calls like JavaScript functions: `add`, `fib`, and a string-returning `greet`. | **563 B**, `--no-gc`, plain MVP module, zero imports | 32 lines, no dependencies |
| [`hello-clack/`](hello-clack) | **Start here if you want Clack.** The smallest real [Clack](https://github.com/fukamachi/clack) application: `ql:quickload`, one `defun`, and `clack:clackup :server :reactor`. No Worker-specific code in the Lisp at all — the compiler synthesizes the exported entry point. | 248 KB (**75 KB gzip**), `--no-wasi` wasm-GC, zero imports | 45 lines, one file, no dependencies |
| [`hello-tiny-routes/`](hello-tiny-routes) | **`hello-clack` with the application composed instead of written.** Three routes through [tiny-routes](https://github.com/jeko2000/tiny-routes) — a `/hello/:name` path template, route declining into a catch-all 404 — and the same `clackup` line. Loaded as **`tiny-routes/lite`**, so no regex engine ships. | 272 KB (**81 KB gzip**), `--no-wasi` wasm-GC, zero imports | `hello-clack/src/index.js`, byte-identical |
| [`httpbin/`](httpbin) | A **mini httpbin**: `/get`, `/post`, `/put`, `/patch`, `/delete` echoing the request as JSON, 405 and 404, and `handler-case` — as a [Clack](https://github.com/fukamachi/clack) application, with the adapter that puts it on a Worker written out by hand so that **clack itself never ships**. The application half is [`examples/net/httpbin-clack.lisp`](../net/httpbin-clack.lisp) verbatim, the same file `httpbin-clack/` deploys whole. | **179 KB** (55 KB gzip), `--no-wasi` wasm-GC, zero imports | 54 lines, one file, no dependencies |
| [`httpbin-clack/`](httpbin-clack) | **The same application again, installed by `clack:clackup` — and there is no `worker.lisp` at all.** `build.sh` compiles [`examples/net/httpbin-clack.lisp`](../net/httpbin-clack.lisp) — the file that serves on the interpreter, the JVM and under `wasmtime serve` — unchanged: under `--no-wasi` the `:server :rontolisp` handler backend takes its reactor shape and the compiler synthesizes the export. One source, four hosts, no adapter written anywhere — for 1.5× the module, because clack and lack (tree-shaken) ship inside it. | 264 KB (**79 KB gzip**), `--no-wasi` wasm-GC, zero imports | `httpbin/src/index.js`, byte-identical |
| [`httpbin-tiny-routes/`](httpbin-tiny-routes) | **`httpbin-clack` with a real routing library.** The same endpoints routed through [tiny-routes](https://github.com/jeko2000/tiny-routes) — `define-routes`, a `/status/:code` path template, route declining — loaded as **`tiny-routes/lite`**, the opt-in system whose ppcre-free path-template matcher keeps cl-ppcre (and 511 KB) out of the module. | 289 KB (**86 KB gzip**), `--no-wasi` wasm-GC, zero imports | `httpbin/src/index.js`, byte-identical |
| [`httpbin-component/`](httpbin-component) | **The same `httpbin` Lisp source**, reached through the component model (`--component --no-wasi` + `jco transpile`) instead of raw linear memory. | 1 core module (179 KB), zero imports | 37 hand-written lines + 95 KB of generated glue |

## Which one should I copy?

`hello/` if your Lisp is numbers and strings — it is by far the smallest thing
that works, and it needs no runtime support at all.

`httpbin/` for anything else. It is the shortest path to the full language on
Workers, its handler is a portable Clack application, and the thirty lines that
put it on Cloudflare are right there in the file rather than in a library.

`hello-clack/` to see what a Clack application on a Worker looks like with
nothing else in the way — three forms, and nothing in the Lisp mentions
Cloudflare at all. The `:server :reactor` designator means "host-driven on
every backend", which is also what lets its `check.lisp` drive the Worker
through `dispatch` on the interpreter.

`hello-tiny-routes/` if you are starting a routed application rather than
studying one. It is `hello-clack/` with the `defun` replaced by `define-routes`
— a path template, a decline, a catch-all — and nothing else changed, so what
the routing library costs (+24 KB raw over `hello-clack/`) is the only
difference between the two directories.

`httpbin-clack/` when you would rather the file read like every other Clack
program than save the space — or already have one. It deploys the *same
application* as `httpbin/` — the same text, the same envelope, the same
`src/index.js` — as the unchanged `examples/net/httpbin-clack.lisp`, `:server
:rontolisp` and all: `clack:clackup` installs the adapter instead of the
program carrying it, for 1.4× the compressed module. Measured there: the
per-request cost is identical; what clack costs is module size and a little
isolate startup.

`httpbin-tiny-routes/` when the routes deserve a library: path templates
(`/status/:code`), route declining, the middleware combinators — the real
tiny-routes API for +25 KB over `httpbin-clack/`, *provided* it is loaded as
`tiny-routes/lite`. The full `"tiny-routes"` spells the same routes but ships
cl-ppcre, which takes the same module to 0.80 MB — its README holds the
four-way size table.

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

That is the whole benefit. The costs, all measured on the identical
`httpbin/worker.lisp`:

| | `httpbin` (`--no-wasi`) | `httpbin-component` (`--component --no-wasi` + jco) |
| --- | --- | --- |
| Files the Worker imports | 1 `.wasm` | 1 `.wasm` + 95 KB generated `.js` |
| Build tools | the rontolisp compiler | + `@bytecodealliance/jco` |
| WASI imports to satisfy | none | none |
| Top-level forms (`defparameter`) | run via `_initialize` | run at instantiation |
| Hand-written glue | 54 lines, boundary included | 37 lines |

Two findings behind that table, neither of them obvious:

1. **jco's default output does not run on Workers.** It calls
   `WebAssembly.compile()` on a base64 blob at module scope, and workerd rejects
   the module with `Top-level await in module is unsettled`. With `--tla-compat`
   the Worker starts but every request hangs instead. The mode that works is
   `--instantiation sync`, where the glue asks *the host* for each compiled core
   module — which is exactly a `.wasm` import.
2. **`handler-case` needs `--bindgen-enable-wasm-exnref`.** Without it jco
   refuses the component outright: `exceptions proposal not enabled`.

`--no-wasi` is doing quiet work in that table. Without it, a wasm-GC component
is a `wasi:cli/run` command by construction: it imports `wasi:cli/stdout`,
`wasi:cli/types` and `wasi:filesystem/types` whether or not the program does
any I/O (three stubs to hand-write), ships two extra core modules, and its
top-level forms live in a `run` export jco cannot drive (`task exited without
resolution`) — so a `defparameter` was a hard constraint on this path. With it,
the compiler emits a **reactor component** that imports nothing, and the top
level runs from the core module's start section inside `instantiate`.

`hello/` composes the same way from the other direction: because the program
fits the `--no-gc` subset the component imports nothing at all, and
`jco transpile --instantiation sync -b 0` produces glue with no dependencies.
Even there it is 92 KB of generated JavaScript to replace about ten lines.

## Developing without Cloudflare

The Lisp in every one of these is an ordinary function, so the whole edit/run
loop happens locally — [`httpbin/check.lisp`](httpbin/check.lisp) and
[`httpbin-tiny-routes/check.lisp`](httpbin-tiny-routes/check.lisp) drive their
handlers on the interpreter, the JVM and wasmtime, and `httpbin-clack/`'s
program IS `../net/httpbin-clack.lisp`, so its loop is `rontolisp
../net/httpbin-clack.lisp` and `curl` — no Cloudflare, no JavaScript. Every
Lisp source here is pinned by `examples/examples.yaml`; while iterating on
them, narrow the suite:

```bash
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false \
       -Drontolisp.examples=true -Drontolisp.examples.only=cloudflare test
# Tests run: 14, instead of 235 in ~7min
```

## Deploying

All but the two tiny-routes directories (verified under `wrangler dev`) were
deployed to the real edge and verified there, not only under
`wrangler dev`:

| | Upload | gzip | Worker Startup Time |
| --- | --- | --- | --- |
| `hello` | 1.88 KiB | **1.12 KiB** | not reported |
| `httpbin` * | 278.53 KiB | **91.03 KiB** | 12-13 ms |
| `httpbin-component` * | 503.84 KiB | **130.21 KiB** | 5 ms |
| `hello-clack` | 1608.77 KiB | **358.27 KiB** | 30 ms |
| `httpbin-clack` | 1654.60 KiB | **371.94 KiB** | 26 ms |

\* the whole table records the last real deploys, and every module has shrunk
since — most recently by the 2026-08-08 dispatch-gate refinement, which halved
the two clack builds, by the builds moving to `--optimize=size` the same day,
the CLOS-lowering pass that followed, the component build becoming a
`--no-wasi` reactor (one core module, a third of the glue), and the
2026-08-09 CLOS-aware library pruning, which took another ~30% out of every
clack build (a Worker never calls the ironclad core that rides in with lack,
and its classes and methods now leave with the rest). Locally the
modules are now `httpbin` **179 KiB / 55 KiB gzip**, `httpbin-clack`
**264 KiB / 79 KiB gzip**, `hello-clack` **248 KiB / 75 KiB gzip**,
`hello-tiny-routes` **272 KiB / 81 KiB gzip**, `httpbin-tiny-routes`
**289 KiB / 86 KiB gzip**, and the component build's single core module
**179 KiB**. Startup time is only reported by a real `wrangler deploy`, so
those cells stand until the next one.

The gzip column is the one that counts: the Worker size limit applies to the
compressed bundle, so even the component build sits well under 5% of the free
plan's 3 MB, and `httpbin-clack` — the largest of the deployed five by a wide margin,
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
