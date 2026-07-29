# Running rontolisp WASM in the browser (plain HTML + JavaScript)

This example shows how to take a Lisp program, compile it to WebAssembly with
rontolisp, and run it in a browser from ordinary HTML and JavaScript — no
framework, no bundler, no server-side component.

It is different from [`web/`](../../web) at the repository root: that playground
compiles **rontolisp itself** to WASM (via GraalVM Web Image). Here we compile a
**user Lisp program** with rontolisp's own WASM backend
(`rontolisp prog.lisp -o prog.wasm`) and call the result from JavaScript.

**Live demo:** <https://making.github.io/rontolisp/wasm-browser/> (this directory
is published as a subpath of the GitHub Pages site by `.github/workflows/pages.yaml`;
the `web/` playground is at the site root).

## What's in here

| File            | Purpose                                                            |
| --------------- | ----------------------------------------------------------------- |
| `index.html`    | The demo page: buttons that run the WASM modules and show stdout. |
| `wasi-shim.js`  | A tiny, dependency-free WASI Preview 1 shim (the glue code).       |
| `hello.lisp` / `hello.wasm` | A self-contained program (Fibonacci + rational math). |
| `greet.lisp` / `greet.wasm` | Reads a line from stdin and greets — shows input.     |
| `dice.lisp` / `dice.wasm`   | Rolls dice with `random` — different result every run. |
| `build.sh`      | Recompiles the `.lisp` files to `.wasm`.                          |

The `.wasm` files are checked in, so you can run the demo without building
anything.

## How it works

A rontolisp-compiled module is a WASI "command": it exports `memory` and
`_start`, and imports eight functions from `wasi_snapshot_preview1`:

```
fd_write  fd_read  path_open  fd_close
random_get  clock_time_get  environ_sizes_get  environ_get
```

There is no host runtime in a browser, so `wasi-shim.js` implements those eight
functions in JavaScript:

- **stdout / stderr** (`fd_write`) are captured into strings instead of a tty.
- **stdin** (`fd_read`) is served from a string you pass in.
- **files** (`path_open`) are unsupported and report "no entry".
- **randomness** uses `crypto.getRandomValues`; the **clock** uses `Date.now()`.
- **environment variables** come from an `env` option.

Running a module is then three steps (see `runWasm` in `wasi-shim.js`):

```js
import { runWasm } from "./wasi-shim.js";

// 1. fetch + instantiate with the shim's imports, 2. call _start,
// 3. collect what it printed.
const { stdout } = await runWasm("./hello.wasm");
console.log(stdout);

// Passing input via stdin:
const res = await runWasm("./greet.wasm", { stdin: "Ada\n" });

// Passing input via environment variables:
const res2 = await runWasm("./prog.wasm", { env: { NAME: "Ada" } });

// If you already have the module bytes in memory (e.g. compiled in the
// browser, with no file to fetch), skip the fetch with runWasmModule:
const res3 = await runWasmModule(wasmBytes, { stdin: "Ada\n" });
```

`runWasm(url)` is just `fetch` + `runWasmModule(bytes)`. The root
[`web/` playground](../../web)'s `compile-run.html` page uses `runWasmModule`
to run a module it compiled in the browser a moment earlier (its
`rontoCompileWasm` returns the bytes directly), so compile-to-WASM and
run-the-WASM both happen client-side. It compiles the definitions once with a
`(print (eval (read)))` driver, then for each call passes the call expression
(e.g. `(fib 20)`) as **stdin** — reusing this same `runWasmModule(bytes, { stdin })`
entry point — without recompiling.

Because the module's only outward interface is stdout (and optionally stdin /
env / exit code), "calling it from JavaScript" means *running it and reading
what it printed* — it is a whole program, not a library of exported functions.

## Run it

The page fetches `.wasm` files, so it must be served over `http://` (opening
`index.html` as a `file://` URL will fail the `fetch`). Any static server works:

```bash
cd examples/browser/wasm-browser
python3 -m http.server 8000
# then open http://localhost:8000/
```

Click **Run hello.wasm** to run the self-contained program, **Run greet.wasm**
to feed the textarea contents to the program as stdin, and **Roll dice.wasm**
(then **Roll again**) to see `random` produce a fresh result each run from the
host's `random_get`.

## Browser requirements

rontolisp emits **WebAssembly GC** (the cons cell is a GC struct), so you need a
browser that supports the WASM GC proposal — enabled by default in:

- Chrome / Edge 119+
- Firefox 120+
- Safari 18.2+

No special flags are required in those versions. (In Node.js the same modules
run under `node` 22+ without flags, which is how the shim is regression-tested.)

## Rebuilding the `.wasm` files

If you edit the `.lisp` sources, rebuild from the repository root:

```bash
./mvnw clean package          # produces target/rontolisp-...-exec.jar
examples/browser/wasm-browser/build.sh
```

## Notes and limitations

- **Input channels.** A WASI command cannot be handed arguments the way a
  function call can. To pass data in, use **stdin** (`read-line`), **environment
  variables** (`uiop:getenv`), or compile the input into the program. This shim
  supports stdin (demoed by `greet.wasm`) and env (pass `{ env: { NAME: "Ada" } }`
  to `runWasm`); command-line args are not wired (rontolisp's output does not
  import `args_get`).
- **Files.** `open` / `load` / `with-open-file` will fail in the browser —
  there is no filesystem behind `path_open`. Use stdin/env instead.
- This shim is intentionally minimal and readable. For a more complete browser
  WASI implementation, use a package such as `@bjorn3/browser_wasi_shim`.
