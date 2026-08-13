# httpbin-component — the same handler, through the component model

This directory compiles **[`../httpbin/worker.lisp`](../httpbin/worker.lisp)** —
the same file, not a copy — as a WebAssembly **reactor component**
(`--component --no-wasi`) and runs it on Cloudflare Workers through
`jco transpile`. Same routes, same responses; a different way of crossing the
boundary.

```bash
./build.sh          # ../httpbin/worker.lisp -> worker.wasm -> src/dist/
npx wrangler dev    # http://localhost:8787
```

## What it buys

The canonical ABI marshals the strings, so the call is a call:

```js
const result = lisp.handleRequest(input);   // string in, string out
```

Compare [`../httpbin/src/index.js`](../httpbin/src/index.js), whose
`handleRequest` allocates, writes bytes into linear memory, reads a `[ptr, len]`
pair back out and pops a bump-allocator arena to do the same thing. That whole
function disappears here. It is the entire benefit, and it is a genuine one.

## What it costs

| | [`../httpbin`](../httpbin) | this directory |
| --- | --- | --- |
| Files the Worker imports | 1 × `.wasm` | 1 × `.wasm` + a generated `worker.js` ([size report](../../../size-report/results/cloudflare-workers.md)) |
| Build tools | the rontolisp compiler | + `@bytecodealliance/jco` |
| WASI imports to satisfy | none | none |
| Top-level `defparameter` | works, via `_initialize` | works, at instantiation |
| The request body | out of band, as octets through `env.readRequestBody` | inside the envelope's `"body"` key |

That last row is the one place the shared source diverges, and it is one
`#-rontolisp-component` in `worker.lisp`: a component's host functions cross the
canonical ABI rather than a core import, so there is no byte-shaped import here
to pull a body through. This build therefore pays what
[`../httpbin`](../httpbin#the-body-is-not-in-the-envelope) stopped paying — the
body JSON-escaped into the head, and no way to carry a binary one.

`--no-wasi` is what makes the right column read that way: it asks for a
component that **imports nothing** (`wasm-tools component wit` shows not a
single `import` line), so the generated glue's `ImportObject` type is literally
empty and `src/index.js` instantiates with `{}`. The Lisp top level runs from
the core module's *start section* inside `instantiate`. Without the flag, the
same build imports three WASI interfaces that must be stubbed by hand, ships two
extra (empty) core modules, and cannot run its top-level forms at all, because
they live in a `wasi:cli/run` export jco cannot drive.

## The two things that are not obvious

**1. jco's default output does not run on Workers.** It calls
`WebAssembly.compile()` on an inlined base64 blob at module scope, and a Worker
may not compile WebAssembly at run time — workerd rejects the module with
`Uncaught Error: Top-level await in module is unsettled.` `--tla-compat` moves
that into an awaited `$init` promise, which is worse: the Worker starts and then
every request hangs. The mode that works is **`--instantiation sync`**, where
the glue asks the host for each already-compiled core module through a
`getCoreModule(path)` callback. A `.wasm` import is exactly that:

```js
import core0 from "./dist/worker.core.wasm";
// ...
instantiate(() => core0, {});
```

`-b 0` goes with it, to stop jco inlining a small core module as base64 rather
than emitting the file we need to import.

**2. `handler-case` needs `--bindgen-enable-wasm-exnref`.** Without it jco
refuses the component before generating anything (`exceptions proposal not
enabled`).

## When this path is actually clean

When the program fits the `--no-gc` subset. [`../hello`](../hello) has no
component build of its own, but try it: `--no-gc --component` is a tiny
component that imports *nothing*, so there is no exnref flag and the glue has no
dependencies:

```bash
JAR=../../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR ../hello/worker.lisp -o worker-no-gc.wasm --no-gc --component --optimize
npx -y @bytecodealliance/jco transpile worker-no-gc.wasm -o worker-no-gc-dist
```

```console
$ node --input-type=module -e '
    import { add, fib, greet } from "./worker-no-gc-dist/worker-no-gc.js";
    console.log(add(2, 3), fib(20), greet());'
5 6765 Hello from Lisp, compiled to WebAssembly!
```

(That plain transpile is enough for Node. For a Worker, add
`--instantiation sync -b 0` and hand the core module over as above.)

Even there it is generated JavaScript standing in for about ten lines of
hand-written glue — which is the honest summary of this whole directory.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/httpbin-component/build.sh
```

`build.sh` prints the core-module file names it produced. A reactor component
has exactly one; if a rebuild ever prints more, the program stopped being
import-free (or the flag went missing) and `src/index.js` needs to hear about it.
