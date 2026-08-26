# rontolisp browser playground

A browser playground that runs the rontolisp **interpreter** and **compilers**
entirely client-side. rontolisp itself is compiled to WebAssembly with the
[GraalVM Native Image Web Image backend](https://www.graalvm.org/latest/reference-manual/web-image/),
so the page has no server-side component.

It offers:

- **REPL** — interpret expressions in a persistent environment (definitions and
  variables survive across inputs). Input history is kept in `localStorage`
  (it survives page reloads) and is navigated with the Up/Down arrow keys.
  The input area accepts multi-line expressions: Shift+Enter inserts a newline,
  Enter evaluates.
- **Compile to JVM** — compile the source to a `.class` file and download it.
  The downloaded file runs on a real JVM (`java Main`).
- **Compile to WASM** — compile the source to a `.wasm` module and download it.
  The downloaded file runs on a real wasm runtime (`wasmtime --wasm gc output.wasm`).

A companion page, **`compile-run.html`** ("compile & run WASM in the browser"),
closes the loop in **two phases**, both client-side:

1. **Compile & Load** — compile a set of Lisp definitions to a `.wasm` module
   (same `rontoCompileWasm`) and keep it. A tiny driver, `(print (eval (read)))`,
   is appended so the module can apply a call read from stdin.
2. **Execute** — enter a function name and arguments; the page builds a call
   expression (e.g. `(fib 20)`), feeds it to the kept module on stdin, and
   **runs it in your browser's own WebAssembly runtime** through the WASI shim
   from [`examples/browser/wasm-browser/`](../examples/browser/wasm-browser) (`runWasmModule`),
   showing the printed result. The module is compiled once and reused, so
   changing the arguments and calling again does **not** recompile.

Because the call is passed as Lisp source through the module's built-in
`read`/`eval`, the value-representation barrier (Lisp values are WASM GC
references, not JS numbers) is sidestepped: arguments are ordinary Lisp
(literals, `(list 1 2 3)`, nested calls), subject to the compiled `eval`'s
limits. So the user program is both compiled to WASM and executed entirely in
the browser, with no download and no server.

**Download .wasm** saves the loaded module (`loaded.wasm`, definitions plus the
`(print (eval (read)))` driver). The page includes a "Run the downloaded
`loaded.wasm` outside the browser" section: because the driver reads the call
from stdin, you run it by piping a call expression in. The same module works on
either runtime:

```bash
# wasmtime (needs WebAssembly GC support)
echo '(fib 20)' | wasmtime run loaded.wasm

# Node.js 22+ — save run.mjs (node:wasi over the preview1 imports),
# then pipe a call to it
echo '(fib 20)' | node run.mjs loaded.wasm
```

The source and REPL panes are resized by dragging the divider between them,
the REPL input area is resized by dragging the bar above it (both positions
are remembered in `localStorage`), and the sample selector loads
ready-to-run programs covering recursion, higher-order functions, closures,
string operations, `format`, list/association-list operations, `setf`/`push`/`pop`,
math (including exact rationals), `eval`, and loops.

## How it works

```
playground.html  (browser UI: REPL + download buttons + fetch broker)
   |  postMessage RPC (vmCall)
   v
ronto-worker.js  (Web Worker hosting the runtime; blocks on Atomics.wait for fetch)
   |  importScripts
   v
rontoplayground.js + .wasm   (rontolisp compiled to WASM by Web Image)
   |  wraps
   v
RontoPlayground.java   (@JS bootstrap that exports 3 functions to JS)
   |  delegates to
   v
LispEvaluator / JvmLispCompiler / WasmLispCompiler   (the existing core)
```

The interpreter runs inside a **Web Worker** (`ronto-worker.js`), so long
evaluations do not freeze the page and `rontolisp:fetch` is truly asynchronous:
the worker posts each request (with a growable `SharedArrayBuffer`) to the main
thread, whose broker runs the real browser `fetch()` concurrently — multiple
requests overlap — and `rontolisp:await` blocks the worker with `Atomics.wait`
until the response bytes land in the buffer. `SharedArrayBuffer` needs
cross-origin isolation (COOP/COEP); GitHub Pages cannot send those headers, so
`coi-serviceworker.min.js` (vendored, MIT) supplies them via a service worker
(one automatic reload on first visit). Without isolation the playground falls
back to a synchronous XHR per request — same behavior, no overlap.

`compile-run.html` reuses the same `rontoplayground.js` runtime but adds the
in-browser execution step:

```
compile-run.html
   |  Phase 1 (once):  globalThis.rontoCompileWasm(definitions + "(print (eval (read)))")
   |                     -> Base64 .wasm bytes (client-side), kept in memory
   |  Phase 2 (per call): runWasmModule(bytes, { stdin: "(fib 20)\n" })
   v                        (./wasm-browser/wasi-shim.js)
browser WebAssembly runtime  -> the module's read+eval applies the call,
                                 print writes the result -> shown on the page
```

The WASI shim is the same file the standalone `wasm-browser/` example serves; it
gained a `runWasmModule(bytes, opts)` entry point (the existing `runWasm(url)` is
now `fetch` + `runWasmModule`) so a module already in memory needs no `.wasm`
file to fetch.

`src/web/java/am/ik/rontolisp/web/RontoPlayground.java` depends on the
GraalVM-only `org.graalvm.webimage.api` module, so it is kept in a separate
source root. The `web` Maven profile adds `src/web/java` to the build (via
`build-helper-maven-plugin`); the normal build and non-GraalVM JDKs never see it.

`RontoPlayground` installs three callables on the JavaScript global scope using
the `@JS` annotation (`@JS.Export` is not implemented in Web Image yet, so the
bootstrap-helper pattern is used). Compiled bytes cross the JS boundary as
Base64 strings; the front-end decodes them into a `Blob` for download.
Compilation errors are returned as strings prefixed with `ERROR:`.

## Prerequisites

- **GraalVM with the Web Image (`svm-wasm`) tool** providing `native-image`.
  Verify with `native-image --tool:svm-wasm --help`.
- **Binaryen** (`wasm-as`) version 119+ on `PATH` (`brew install binaryen`).
- A recent browser with **WebAssembly GC and exception handling** (verified on
  Chrome 149). For Node.js, run with `node --experimental-wasm-exnref`.

## Build

```bash
./mvnw -Pweb -DskipTests package
```

The `web` profile compiles `src/web/java` together with the rest of the project,
then runs the `native-maven-plugin` with `--tool:svm-wasm` to compile rontolisp
to WebAssembly, and stages `rontoplayground.js`, `rontoplayground.js.wasm`,
`playground.html` (the UI), `ronto-worker.js` (the Web Worker host),
`coi-serviceworker.min.js` (COOP/COEP for GitHub Pages), and `index.html` (a
redirect to the docs) into `web/dist/`. Build with a GraalVM that has the
`svm-wasm` tool.

Profile-specific details (all confined to the `web` profile):

- The release flag is cleared (`maven.compiler.release` is emptied) and
  `-parameters` is enabled — `--release` hides the `org.graalvm.webimage.api`
  module, and `@JS` needs parameter names in the bytecode.
- jline stays on the classpath only so `JLineRepl` keeps compiling; it is unused
  by the playground. Its embedded `META-INF/native-image` config is excluded
  (`--exclude-config`), which otherwise force-includes jline and makes the wasm
  roughly 5x larger.

## Run

The page must be served over HTTP (the `.wasm` is fetched relative to the page):

```bash
cd web/dist
jwebserver -p 8000          # or: python3 -m http.server 8000
open http://localhost:8000/playground.html   # / redirects to the docs
```

## Deployment

`.github/workflows/pages.yaml` builds the playground and publishes `web/dist` to
GitHub Pages on every push to `develop` (and on manual `workflow_dispatch`).
All asset references are relative, so it works under the project subpath:

> https://making.github.io/rontolisp/

The `package` phase also stages two checked-in examples as subpaths of the same
site (no compilation needed — their `.wasm` files are committed and their
`index.html` uses relative URLs):

- `/wasm-browser/` — prebuilt WASM run in plain HTML/JS
  (from [`examples/browser/wasm-browser/`](../examples/browser/wasm-browser)).
- `/hiragana/` — handwritten-hiragana recognition demo
  (from [`examples/browser/hiragana/`](../examples/browser/hiragana); only the five runtime files
  the browser loads — `index.html`, `wasi-shim.js`, `glyphs.js`, `infer.wasm` and the trained
  `weights.bin` the module reads at startup — are staged; the offline training
  artifacts are excluded).

One-time repo setup: **Settings -> Pages -> Build and deployment -> Source:
"GitHub Actions"**.

## Notes / limitations

- `read` (stdin) is unavailable in the WASM sandbox; the REPL covers the
  interpreter's evaluation features. See the project README "Compiled `eval`
  limitations" for the compiled backends.
- `rontolisp:fetch` (HTTP) works in the browser, but through a different transport.
  `java.net.http` cannot be compiled by Web Image (it needs the TLS/security stack
  and host sockets), and Web Image has neither JS Promise Integration nor threads,
  so a WASM guest cannot `await` a browser Promise directly. Instead, a Web Image
  substitution (`src/web/java/.../eval/Target_HttpSupport.java`) hands the request
  to the **main-thread fetch broker** over a `SharedArrayBuffer`
  (`src/web/java/.../eval/BrowserHttp.java`) and `await` blocks the worker with
  `Atomics.wait` — requests genuinely overlap. On the main thread (e.g.
  `compile-run.html`) or without cross-origin isolation it falls back to a
  **synchronous `XMLHttpRequest`**. Either way requests are subject to the browser
  **same-origin policy / CORS** (cross-origin targets must send
  `Access-Control-Allow-Origin`, and only "simple" response headers appear in
  `:headers` unless the server sets `Access-Control-Expose-Headers`); a blocked or
  failed request surfaces as an error when the promise is awaited. (Compiling a
  `fetch` program to JVM still works; compiling to WASM needs `--component`, which
  the playground does not emit.)
- `load` works against uploaded files: pick (or drag-and-drop) `.lisp` files
  with the **load files** control, then `(load "name.lisp")` resolves them from
  an in-memory map. The browser has no real filesystem, so the playground
  installs an in-memory `SourceLoader` (`globalThis.rontoPutFile(name, content)`
  feeds it) instead of `Files.readString`.
- Generated artifacts (`web/dist/`, and `target/rontoplayground.*`) are
  git-ignored.
