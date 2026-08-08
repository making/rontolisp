# httpbin-component — the same handler, through the component model

This directory compiles **[`../httpbin/worker.lisp`](../httpbin/worker.lisp)** —
the same file, not a copy — as a WebAssembly component and runs it on Cloudflare
Workers through `jco transpile`. Same routes, same responses; a different way of
crossing the boundary.

```bash
./build.sh          # ../httpbin/worker.lisp -> worker.wasm -> src/dist/
npx wrangler dev    # http://localhost:8787
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"method":"GET","headers":{...},"path":"/get","args":{"b":"two","a":"1"}}
$ curl -X POST -d '{not json' http://localhost:8787/post
{"data":"{not json","args":{},"json":null,"method":"POST",...}
```

## What it buys

The canonical ABI marshals the strings, so the call is a call:

```js
const result = lisp.handleRequest(input);   // string in, string out
```

Compare [`../httpbin/src/index.js`](../httpbin/src/index.js), whose `handleRequest`
allocates, writes bytes into linear memory, reads a `[ptr, len]` pair back out
and pops a bump-allocator arena to do the same thing. That whole function
disappears here. It is the entire benefit, and it is a genuine one.

## What it costs

| | [`../httpbin`](../httpbin) | this directory |
| --- | --- | --- |
| Files the Worker imports | 1 × `.wasm` (195 KB) | 3 × `.wasm` (207 KB) + `worker.js` (293 KB) |
| Build tools | the rontolisp compiler | + `@bytecodealliance/jco` |
| WASI imports to satisfy | none | 3 interfaces, stubbed by hand |
| Top-level `defparameter` | works, via `_initialize` | does not work at all |

## The three things that are not obvious

Each of these was found the hard way; `build.sh` and `src/index.js` carry the
answers.

**1. jco's default output does not run on Workers.** It calls
`WebAssembly.compile()` on an inlined base64 blob at module scope, and a Worker
may not compile WebAssembly at run time. workerd rejects the module:

```
Uncaught Error: Top-level await in module is unsettled.
```

`--tla-compat` moves that into an awaited `$init` promise, which is worse — the
Worker starts and then every request hangs until the runtime cancels it. The mode
that works is **`--instantiation sync`**: the generated glue does not compile
anything, it asks the host for each already-compiled core module through a
`getCoreModule(path)` callback. A `.wasm` import is exactly that, so
`src/index.js` hands them over:

```js
import core0 from "./dist/worker.core.wasm";
// ...
instantiate((path) => CORE_MODULES[path], WASI_STUBS);
```

`-b 0` goes with it, to stop jco inlining a small core module as base64 rather
than emitting the file we need to import.

**2. `handler-case` needs `--bindgen-enable-wasm-exnref`.** Without that flag jco
refuses the component before generating anything:

```
ComponentError: failed to translate component
Caused by: exceptions proposal not enabled (at offset 0xb4e)
```

**3. Top-level forms cannot be run.** A component's top-level forms live in its
`wasi:cli/run` export, and calling that through jco fails:

```
Error: (component [0]) task [1] exited without resolution
```

— jco cannot drive a stackful-async export. So there is no `_initialize`
equivalent on this path, and `worker.lisp` is only buildable here because every
piece of its state lives inside a `defun` — there is not one `defparameter` in
it, and adding one would be a constraint of this directory rather than of the
Lisp. Miss that and every top-level definition is silently `nil`: the symptom is
a route quietly answering `false`.

## The WASI stubs

A wasm-GC component is a `wasi:cli/run` command by construction, so it imports
`wasi:cli/stdout`, `wasi:cli/types` and `wasi:filesystem/types` whether or not
the program does any I/O. With `--instantiation sync` those become an import
object the host supplies, and since `worker.lisp` never prints, stubs are
enough:

```js
const WASI_STUBS = {
  "wasi:cli/stdout": { writeViaStream: async () => ({ tag: "ok", val: undefined }) },
  "wasi:cli/types": {},
  "wasi:filesystem/types": { Descriptor: class Descriptor {} },
};
```

Note the keys are the **unversioned** interface names, even though the generated
`.d.ts` writes them with `@0.3.0`.

## When this path is actually clean

When the program fits the `--no-gc` subset. [`../hello`](../hello) has no
component build of its own, but try it: `--no-gc --component` is an 834-byte
component that imports *nothing*, so there are no stubs and no exnref flag, and
the glue has no dependencies:

The output name says which build it is, so it cannot be confused with the
`worker.wasm` this directory's own `build.sh` produces:

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

Even there it is 92 KB of generated JavaScript standing in for about ten lines of
hand-written glue — which is the honest summary of this whole directory.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/httpbin-component/build.sh
```

`build.sh` prints the core-module file names it produced. If they ever differ
from the three `src/index.js` imports — the count can change with the program —
update that list.
