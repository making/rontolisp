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

The source and REPL panes are resized by dragging the divider between them,
the REPL input area is resized by dragging the bar above it (both positions
are remembered in `localStorage`), and the sample selector loads
ready-to-run programs covering recursion, higher-order functions, closures,
string operations, `format`, list/association-list operations, `setf`/`push`/`pop`,
math (including exact rationals), `eval`, and loops.

## How it works

```
index.html  (browser UI: REPL + download buttons)
   |  calls globalThis.rontoEval / rontoCompileJvm / rontoCompileWasm
   v
rontoplayground.js + .wasm   (rontolisp compiled to WASM by Web Image)
   |  wraps
   v
RontoPlayground.java   (@JS bootstrap that exports 3 functions to JS)
   |  delegates to
   v
LispEvaluator / JvmLispCompiler / WasmLispCompiler   (the existing core)
```

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
to WebAssembly, and stages `rontoplayground.js`, `rontoplayground.js.wasm`, and
`index.html` into `web/dist/`. Build with a GraalVM that has the `svm-wasm` tool.

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
open http://localhost:8000
```

## Deployment

`.github/workflows/pages.yaml` builds the playground and publishes `web/dist` to
GitHub Pages on every push to `develop` (and on manual `workflow_dispatch`).
All asset references are relative, so it works under the project subpath:

> https://making.github.io/rontolisp/

One-time repo setup: **Settings -> Pages -> Build and deployment -> Source:
"GitHub Actions"**.

## Notes / limitations

- `read` (stdin) is unavailable in the WASM sandbox; the REPL covers the
  interpreter's evaluation features. See the project README "Compiled `eval`
  limitations" for the compiled backends.
- `rontolisp:fetch` (HTTP) works in the browser, but through a different transport.
  `java.net.http` cannot be compiled by Web Image (it needs the TLS/security stack
  and host sockets), so a Web Image substitution
  (`src/web/java/.../eval/Target_HttpSupport.java`) routes `fetch` to a
  **synchronous `XMLHttpRequest`** (`src/web/java/.../web/BrowserHttp.java`). A
  synchronous XHR is used rather than the browser `fetch()` API because the
  interpreter's `fetch` is a synchronous call and a WASM guest cannot `await` a
  Promise without JS Promise Integration. Consequences: requests are subject to
  the browser **same-origin policy / CORS** (cross-origin targets must send
  `Access-Control-Allow-Origin`, and only "simple" response headers appear in
  `:headers` unless the server sets `Access-Control-Expose-Headers`); a blocked or
  failed request surfaces as a REPL error. (Compiling a `fetch` program to JVM
  still works; compiling to WASM needs `--component`, which the playground does
  not emit.)
- `load` works against uploaded files: pick (or drag-and-drop) `.lisp` files
  with the **load files** control, then `(load "name.lisp")` resolves them from
  an in-memory map. The browser has no real filesystem, so the playground
  installs an in-memory `SourceLoader` (`globalThis.rontoPutFile(name, content)`
  feeds it) instead of `Files.readString`.
- Generated artifacts (`web/dist/`, and `target/rontoplayground.*`) are
  git-ignored.
