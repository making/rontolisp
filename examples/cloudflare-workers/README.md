# rontolisp on Cloudflare Workers

Common Lisp compiled to WebAssembly, running on Cloudflare Workers. Each
directory is a complete, independent Worker project: `./build.sh && npx wrangler
dev`, then `npx wrangler deploy`.

Module sizes are measured rather than quoted here:
[`size-report/results/cloudflare-workers.md`](../../size-report/results/cloudflare-workers.md).

| Directory | What it is | Host glue |
| --- | --- | --- |
| [`hello/`](hello) | **Start here.** Three Lisp functions the Worker calls like JavaScript ones: `add`, `fib`, a string-returning `greet`. `--no-gc`, a plain MVP module with zero imports | 32 lines, no dependencies |
| [`hello-clack/`](hello-clack) | **Start here if you want Clack.** The smallest real [Clack](https://github.com/fukamachi/clack) application: `ql:quickload`, one `defun`, `clack:clackup :server :reactor`. Nothing in the Lisp mentions Cloudflare — the compiler synthesizes the exported entry point | 45 lines, one file |
| [`hello-tiny-routes/`](hello-tiny-routes) | `hello-clack` with the application *composed* instead of written: three routes through [tiny-routes](https://github.com/jeko2000/tiny-routes), a `/hello/:name` template, a decline into a catch-all 404. Loaded as `tiny-routes/lite`, so no regex engine ships | `hello-clack/src/index.js`, byte-identical |
| [`hello-ningle/`](hello-ningle) | `hello-clack` with [ningle](https://github.com/fukamachi/ningle) — the third shape: routes hang on a CLOS *object*, a bare string is a controller, and the 404 is an overridden `ningle:not-found` **method**. The heaviest of the three: ningle reads every request through the `lack-request` chain | `hello-clack/src/index.js`, byte-identical |
| [`httpbin/`](httpbin) | A **mini httpbin** — five echo endpoints, 405, 404, `handler-case` — as a Clack application whose Worker adapter is written out by hand, so that clack itself never ships. The application half is [`net/httpbin-clack.lisp`](../net/httpbin-clack.lisp) verbatim | 54 lines, boundary included |
| [`httpbin-clack/`](httpbin-clack) | **The same application, installed by `clack:clackup`** instead of by a hand-written adapter: `ql:quickload`, the endpoints, `clackup :server :reactor`. The compiler synthesizes the export, and nothing in the Lisp mentions Cloudflare | `httpbin/src/index.js`, byte-identical |
| [`httpbin-clack-one-source/`](httpbin-clack-one-source) | `httpbin-clack` **with no `worker.lisp` at all**: `build.sh` compiles [`net/httpbin-clack.lisp`](../net/httpbin-clack.lisp) unchanged, the same file that binds a socket locally. Under `--no-wasi` the `:server :rontolisp` backend takes its reactor shape — one source, four hosts | `httpbin/src/index.js`, byte-identical |
| [`httpbin-tiny-routes/`](httpbin-tiny-routes) | `httpbin-clack` with a real routing library: `define-routes`, a `/status/:code` template, route declining. Loaded as `tiny-routes/lite`, whose ppcre-free matcher keeps cl-ppcre out of the module | `httpbin/src/index.js`, byte-identical |
| [`httpbin-ningle/`](httpbin-ningle) | The same endpoints in [ningle](https://github.com/fukamachi/ningle)'s own model, and **deliberately not the same code**: routes assigned to a CLOS object in a loop, a controller that returns a string and mutates `*response*`, a request that arrives already parsed, an `:ANY` fallback rule per path and a regex rule that declines | `httpbin/src/index.js`, byte-identical |
| [`httpbin-component/`](httpbin-component) | The same `httpbin` source reached through the component model (`--component --no-wasi` + `jco transpile`) instead of raw linear memory | 37 lines + generated glue |

## Which one should I copy?

- **`hello/`** if your Lisp is numbers and strings. By far the smallest thing
  that works, and it needs no runtime support at all.
- **`httpbin/`** for anything else — the shortest path to the full language on
  Workers, with the thirty lines that put it on Cloudflare in the file rather
  than in a library.
- **`hello-clack/`** to see a Clack application with nothing else in the way.
  `:server :reactor` means "host-driven on every backend", which is what lets
  its `check.lisp` drive the Worker on the interpreter.
- **`hello-tiny-routes/`** if you are starting a routed application rather than
  studying one: `hello-clack/` with the `defun` replaced by `define-routes` and
  nothing else changed.
- **`hello-ningle/`** to compare the two routing models on the smallest possible
  application, before reading `httpbin-ningle/`, which is where ningle's own way
  of writing one is actually spelled out.
- **`httpbin-clack/`** when the file should read like every other Clack program.
  It ships the *same* application text as `httpbin/` with `clack:clackup`
  installing the adapter instead of the program carrying it. Measured there: the
  per-request cost is identical; what clack costs is module size and a little
  isolate startup.
- **`httpbin-clack-one-source/`** when the program already exists and serves
  somewhere else. It has no Lisp of its own: `:server :rontolisp` picks the
  transport from the compile target, so `net/httpbin-clack.lisp` is a socket
  server locally and this Worker here, with no edit between them.
- **`httpbin-tiny-routes/`** when the routes deserve a library — path templates,
  declining, the middleware combinators — *provided* it is loaded as
  `tiny-routes/lite`. Full `"tiny-routes"` spells the same routes but ships
  cl-ppcre.
- **`httpbin-ningle/`** if you already write ningle, or to see the two routing
  models answer the same endpoints without being written the same way. It is by
  an order of magnitude the largest and slowest to start of the four httpbin
  Workers, and the reason is not ningle: it reads every request through the
  `lack-request` chain, which is also what lets its controllers ignore streams
  and JSON parsing entirely.
- **`httpbin-component/`** answers a question rather than being a
  recommendation: *wouldn't the component model be simpler?* For the string
  marshalling, yes. Everywhere else, no.

## What is settled about Workers

All verified end to end under `npx wrangler dev` (workerd), not inferred:

- **wasm-GC runs.** rontolisp's default output is WebAssembly GC, and workerd is
  V8, which has had WasmGC on by default since Chrome 119. No flag, no setting.
- **wasm exception handling runs**, also with no flag — so `handler-case` can
  answer 500 from inside the Lisp. Under wasmtime the same module needs
  `-W exceptions=y`.
- **A Worker may not compile WebAssembly at run time.** `import module from
  "./x.wasm"` gives an already-compiled `WebAssembly.Module`; anything calling
  `WebAssembly.compile()` on bytes hangs at startup. That one fact shapes the
  `httpbin-component/` build.
- **An instance is per isolate, not per request.** Instantiate once and cache
  it, and remember Lisp globals then persist between that isolate's requests.
  Anything that must really persist belongs in KV, D1 or a Durable Object.
- **wasm-GC does not cover the boundary.** Inside the module cons cells and Lisp
  strings are engine-managed. But WebAssembly has no string type, so a string
  crossing the boundary is UTF-8 bytes in **linear memory**, which the engine
  never traces — those bytes are the host's to reclaim. That is what the
  `__ronto_alloc_mark`/`_reset` bracket in `httpbin/src/index.js` is, and why
  `hello/` needs no such code.
  [Details](httpbin/README.md#two-heaps-wasm-gc-collects-one-of-them-you-collect-the-other).

## Would the component model be simpler?

Only in one place, and it is a real one:

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

The costs, measured on the identical `httpbin/worker.lisp`: the Worker imports a
generated `.js` beside the `.wasm`, the build needs `@bytecodealliance/jco`, and
the top-level forms move from `_initialize` to instantiation. Two non-obvious
findings behind it:

1. **jco's default output does not run on Workers.** It calls
   `WebAssembly.compile()` on a base64 blob at module scope, and workerd rejects
   the module with `Top-level await in module is unsettled`; `--tla-compat`
   starts but hangs every request. The mode that works is
   `--instantiation sync`, where the glue asks *the host* for each compiled core
   module — exactly a `.wasm` import.
2. **`handler-case` needs `--bindgen-enable-wasm-exnref`**, or jco refuses the
   component outright.

`--no-wasi` is doing quiet work there. Without it a wasm-GC component is a
`wasi:cli/run` command by construction: it imports `wasi:cli/stdout`,
`wasi:cli/types` and `wasi:filesystem/types` whether or not the program does any
I/O, ships two extra core modules, and its top-level forms live in a `run`
export jco cannot drive — so a `defparameter` was a hard constraint on this
path. With it the compiler emits a **reactor component** that imports nothing,
and the top level runs from the core module's start section inside
`instantiate`.

## Developing without Cloudflare

The Lisp in every one of these is an ordinary function, so the whole edit/run
loop happens locally: every directory with a handler in it has a `check.lisp`
that drives it on the interpreter, the JVM and wasmtime.
`httpbin-clack-one-source/` has no
`check.lisp` because it needs none — its program IS `../net/httpbin-clack.lisp`,
so its loop is `rontolisp ../net/httpbin-clack.lisp` and `curl` against a real
server. Every Lisp source here is pinned by `examples/examples.yaml`:

```bash
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false \
       -Drontolisp.examples=true -Drontolisp.examples.only=cloudflare test
```

## Deploying

The `hello`, `hello-clack`, `httpbin`, `httpbin-clack-one-source` and
`httpbin-component` directories have been deployed to the real edge, not only
run under `wrangler dev`; the two tiny-routes and two ningle ones have not (they
are pinned locally instead, by their `check.lisp` and by node driving the built
module). `httpbin-clack` builds the same application as
`httpbin-clack-one-source` from its own file, and answers identically under
node.
Worker Startup Time is what `wrangler deploy`
measures and budget-checks: 5-13 ms for the `httpbin` builds, 26-30 ms for the
clack ones — going through `clack:clackup` instead of calling the handler
backend directly is what moved that number, while the per-request cost did not.
It is also why `httpbin/src/index.js` instantiates at **module scope** rather
than on the first request: both work, but at module scope the cost is paid once
per isolate outside the request path and Cloudflare validates it at deploy time.

**One gotcha, and it is not your code**: for several minutes after a Worker's
*first* deploy its fresh `*.workers.dev` hostname answers intermittently with
Cloudflare edge errors (`1042`, `1104`, a bare 404) while the route propagates.
The giveaway is that `wrangler tail` shows nothing for those requests — they
never reach the Worker. It settles on its own; re-deploys do not show it.

## Building

Every `build.sh` needs the compiler jar, built once from the repository root
with `./mvnw clean package -DskipTests`. The `.wasm` files are build products
and are not checked in.

### The `--no-wasi` warnings a build prints

A `--no-wasi` build names, per primitive, every refusal its **load path** can
reach — the ones that would otherwise die inside `_initialize` as a bare
`RuntimeError: unreachable`, before any export exists. The line carries the call
chain and the way out (`__ronto_set_time` for the clock, `--host-random` for
entropy); a primitive only the *export* can reach stays quiet, because that one
signals at the call, where `src/index.js` sees it.

None of these directories prints one today. The clack-based ones used to print a
standing line for `WITH-OPEN-FILE`, reached through
`CLACK:CLACKUP -> CLACK:EVAL-FILE -> CLACK::%LOAD-FILE` — `clackup`'s "the app is
a pathname" branch, statically reachable and never taken here. A call now carries
what the site says about its arguments (`#'app`, `*app*`), so a `typecase` branch
that value cannot select is off the load path; under `--optimize` the branch is
off the *module* too.
[Details](../../doc/en/guides/wasm-gc-module.md#what-the-build-tells-you-before-you-run-it).
