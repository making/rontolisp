# rontolisp on Cloudflare Workers

Common Lisp compiled to WebAssembly, running on Cloudflare Workers. Each
directory is a complete, independent Worker project: `./build.sh && npx wrangler
dev`, then `npx wrangler deploy`.

Two subjects — a greeting and a mini [httpbin](https://httpbin.org) — written
once with **no library** and then once in the idiom of each web library. The
point of the set is how differently the same endpoints come out, so the versions
share no code and are not meant to diff cleanly against each other. Two more
directories are about the **boundary** rather than the library:
[`dog-fetcher/`](dog-fetcher) and [`btc-ticker/`](btc-ticker) both call out over
HTTP, on the two shapes `--host-boundary` chooses between, and
[`dog-relay/`](dog-relay) is the streaming shape compiled `--reentrant`, serving
its requests overlapped on one instance.

Module sizes are measured rather than quoted here:
[`size-report/results/cloudflare-workers.md`](../../size-report/results/cloudflare-workers.md).

| Directory | Written as | Host glue |
| --- | --- | --- |
| [`hello/`](hello) | **Start here.** Three `wasm-export`ed functions JavaScript calls directly. `--no-gc`, a plain MVP module with zero imports | 32 lines, no dependencies |
| [`hello-clack/`](hello-clack) | **Start here if you want Clack.** One application function and `clack:clackup` — the whole of [Clack](https://github.com/fukamachi/clack)'s API | GENERATED, and all of it: `src/index.js` is three lines |
| [`hello-clack-one-source/`](hello-clack-one-source) | **No `worker.lisp` at all**, and the smallest program that says so: `build.sh` compiles [`net/hello-clack.lisp`](../net/hello-clack.lisp) unchanged — the file that also binds a socket locally, deploys as a Servlet war and serves under `wasmtime serve`. One source, five hosts | GENERATED — the same file `hello-clack` gets |
| [`hello-tiny-routes/`](hello-tiny-routes) | [tiny-routes](https://github.com/jeko2000/tiny-routes): a route table composed with `define-routes`, threaded through middleware with `pipe`. Loaded as `tiny-routes/lite`, so no regex engine ships | GENERATED — the same file `hello-clack` gets, because the declarations are the same |
| [`hello-ningle/`](hello-ningle) | [ningle](https://github.com/fukamachi/ningle): routes assigned to a CLOS *object*, a bare string as a controller, an overridden `not-found` **method** | GENERATED — the same file `hello-clack` gets, because the declarations are the same |
| [`httpbin/`](httpbin) | **No library.** Five echo endpoints, 405, 404, `handler-case` — plus the reactor adapter written out by hand, so clack never ships | 54 lines, boundary included — the one hand-written host left here |
| [`httpbin-clack/`](httpbin-clack) | Plain Clack: an application *function*, a `cond` over `:path-info`, and one middleware — a function from application to application | GENERATED, and all of it: `src/index.js` is a `worker(module)` call |
| [`httpbin-clack-one-source/`](httpbin-clack-one-source) | **No `worker.lisp` at all**: `build.sh` compiles [`net/httpbin-clack.lisp`](../net/httpbin-clack.lisp) unchanged, the file that binds a socket locally. One source, four hosts | GENERATED — the same file `httpbin-clack` gets |
| [`httpbin-tiny-routes/`](httpbin-tiny-routes) | tiny-routes: route macros, a `/status/:code` template, declining, and middleware that reads the body, parses the query and sets the content type | GENERATED — the same file `httpbin-clack` gets |
| [`httpbin-ningle/`](httpbin-ningle) | ningle: routes assigned in a loop, a controller that returns a string and mutates `*response*`, a request that arrives already parsed, a regex rule that declines | GENERATED — the same file `httpbin-clack` gets |
| [`httpbin-component/`](httpbin-component) | The same `httpbin` source through the component model (`--component --no-wasi` + `jco transpile`) instead of raw linear memory | 37 lines + generated glue |
| [`dog-fetcher/`](dog-fetcher) | **Outgoing HTTP, streaming bodies.** A proxy over [dog.ceo](https://dog.ceo), routed with `tiny-routes/lite`. `rontolisp:fetch` is wasi:http and a reactor has no WASI, so the client is the host's own `fetch`, imported and bridged with JSPI | GENERATED, and all of it: `src/index.js` is three lines |
| [`btc-ticker/`](btc-ticker) | **The Worker with nothing in it.** One outgoing request, one JSON answer — on the DEFAULT boundary, where every body rides the envelope and the module imports exactly one function | GENERATED, and all of it: `src/index.js` is three lines |
| [`dog-relay/`](dog-relay) | **Overlapped calls, relayed bodies.** Forwards dog.ceo's own reply chunk by chunk, and is compiled `--reentrant` so concurrent requests overlap on ONE instance instead of queueing — the streaming boundary with a call id on every body import | GENERATED, and all of it: `src/index.js` is three lines |

## Which one should I copy?

- **`hello/`** if your Lisp is numbers and strings — by far the smallest thing
  that works, and it needs no runtime support at all.
- **`httpbin/`** for anything else without a library: the shortest path to the
  full language on Workers, with the thirty lines that put it on Cloudflare in
  the file rather than in a library.
- **`httpbin-clack/`** when the file should read like every other Clack program.
  The per-request cost is the same as `httpbin/`'s; what clack costs is module
  size and a little isolate startup, paid once.
- **`hello-clack-one-source/`** for the same reason as
  `httpbin-clack-one-source/` below, at the smallest size there is: it is the
  one directory here whose Lisp file lives outside it, and the shortest answer
  to "what does deploying an existing server here cost me?" — a compile flag.
- **`httpbin-clack-one-source/`** when the program already serves somewhere
  else. `:server :rontolisp` picks the transport from the compile target, so
  there is no edit between a local server and this Worker.
- **`httpbin-tiny-routes/`** when the routes deserve a library — templates,
  declining, middleware combinators — *provided* it is loaded as
  `tiny-routes/lite`. Full `"tiny-routes"` spells the same routes and ships
  cl-ppcre.
- **`httpbin-ningle/`** if you already write ningle. It is by an order of
  magnitude the largest and slowest to start of the four, and the reason is not
  ningle: it reads every request through the `lack-request` chain, which is also
  what lets its controllers ignore streams and JSON parsing entirely.
- **`btc-ticker/`** when the Worker fetches a document and answers a document —
  which is most of them, and which is what a build gets for saying nothing: the
  module imports one host function, nothing on the JavaScript side keeps state,
  and there is no cursor whose lifetime anyone has to get right.
- **`dog-fetcher/`** when a body is **binary, large, or relayed** — an image, an
  upload, an upstream reply to forward as it arrives. Those are the cases the
  envelope cannot serve (it carries a body as JSON text, so a non-UTF-8 byte does
  not survive, and it holds the whole thing in memory), so its `build.sh` ASKS
  for `--host-boundary=streaming`. It costs no more JavaScript: the glue writes
  the body imports too, so this `src/index.js` is also three lines. It is where
  the synchronous-Lisp/asynchronous-JavaScript seam is explained in full; both
  directories rely on it.
- **`dog-relay/`** when the Worker is I/O-bound and cannot afford a queue: each
  request is mostly time parked on an upstream, so `--reentrant` lets them
  overlap on one instance (six concurrent relays in about one round trip). It
  is dog-fetcher's boundary plus that flag; read dog-fetcher first.
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
- **JSPI runs**, again with no flag and no compatibility-date opt-in, so a
  synchronous wasm import can be answered by an `async` JavaScript function —
  which is what lets a reactor, whose `rontolisp:fetch` is unavailable, make
  outgoing HTTP requests at all. [`dog-fetcher/`](dog-fetcher) is that, and
  spells out what suspending costs: while a handler is parked the isolate is
  free, so concurrent requests have to be serialised by hand.
- **A Worker may not compile WebAssembly at run time.** `import module from
  "./x.wasm"` gives an already-compiled `WebAssembly.Module`; anything calling
  `WebAssembly.compile()` on bytes hangs at startup. That one fact shapes the
  `httpbin-component/` build.
- **An instance is per isolate, not per request.** Instantiate once and cache
  it, and remember Lisp globals then persist between that isolate's requests.
  Anything that must really persist belongs in KV, D1 or a Durable Object.
- **wasm-GC does not cover the boundary.** Inside the module everything is
  engine-managed, but WebAssembly has no string type, so a string crossing the
  boundary is UTF-8 bytes in **linear memory**, which the engine never traces.
  That is what the `__ronto_alloc_mark`/`_reset` bracket in
  `httpbin/src/index.js` is, and why `hello/` needs no such code.
  [Details](httpbin/README.md#two-heaps).

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
`wasi:cli/run` command by construction: it imports three WASI interfaces whether
or not the program does any I/O, ships two extra core modules, and its top-level
forms live in a `run` export jco cannot drive. With it the compiler emits a
**reactor component** that imports nothing, and the top level runs from the core
module's start section inside `instantiate`.

## Developing without Cloudflare

The Lisp in every one of these is an ordinary function, so the whole edit/run
loop happens locally: every directory with a handler has a `check.lisp` that
drives it on the interpreter, the JVM and wasmtime. `httpbin/`'s goes one step
further and asserts each answer with
[rove](../../doc/en/guides/testing.md), so it exits non-zero when the handler
drifts — which is why that one needs rove's directories on `--system-path`.
`httpbin-clack-one-source/` needs none — its program IS
`../net/httpbin-clack.lisp`, so its loop is serving that file and `curl`; and
`dog-fetcher/` and `btc-ticker/` cannot have one, because their HTTP client is
an import only a Worker provides. Every other Lisp source here is pinned by
`examples/examples.yaml`:

```bash
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false \
       -Drontolisp.examples=true -Drontolisp.examples.only=cloudflare test
```

## Deploying

**Eleven of the twelve are deployed to the real edge**, not only run under
`wrangler dev`, and every endpoint in the tables above was checked there with
`curl` — including the 405, the 404, the unparseable body, both `/status`
answers, and `dog-fetcher/`'s outgoing request, whose JSPI bridge needs nothing
on the edge that it did not need locally. `btc-ticker/` is the exception so far:
it has been driven end to end against the real bitFlyer API through its own
generated `worker()` on node 24 JSPI, which is the same code path workerd runs,
but not yet deployed.

Cloudflare budget-checks **Worker Startup Time** at deploy, and `wrangler
deploy` prints it when it has one to report. Going through `clack:clackup`
instead of calling the handler backend directly is what moves that number; the
per-request cost does not follow it. Module scope would be the nicer place to
pay it — that is where the deploy-time check can see it — but a Worker forbids
drawing entropy there, and the seed has to be in before `_initialize` runs the
Lisp top level. So every host here, hand-written or generated, instantiates on
the **first request** instead, and nothing checks the cost at deploy time.

**One gotcha, and it is not your code**: for several minutes after a Worker's
*first* deploy its fresh `*.workers.dev` hostname answers intermittently with
Cloudflare edge errors (`1042`, `1104`, a bare 404) while the route propagates.
The giveaway is that `wrangler tail` shows nothing for those requests. It settles
on its own; re-deploys do not show it.

## Building

Every `build.sh` needs the compiler jar, built once from the repository root
with `./mvnw clean package -DskipTests`. The `.wasm` files are build products
and are not checked in.

A `--no-wasi` build names, per primitive, every refusal its **load path** can
reach — the ones that would otherwise die inside `_initialize` as a bare
`RuntimeError: unreachable`, before any export exists. The line carries the call
chain and the way out (`__ronto_set_time` for the clock, `--host-random` for
entropy); a primitive only the *export* can reach stays quiet, because that one
signals at the call, where `src/index.js` sees it. None of these directories
prints one today.
[Details](../../doc/en/guides/wasm-gc-module.md#what-the-build-tells-you-before-you-run-it).
