# A WebAssembly component in the browser

An interactive Mandelbrot / Julia explorer whose every pixel is computed by
[`fractal.lisp`](fractal.lisp), compiled to a **WebAssembly component** and
loaded by [`index.html`](index.html) with nothing else at all: no
`WebAssembly.instantiate`, no import object, no WASI shim, no `memory.buffer`,
no bump allocator, no `(ptr, len)` pair to decode.

Every other browser demo in this tree loads a raw **core module** and pays for
it in the page. [`rainbow`](../rainbow) copies UTF-8 bytes into the module's
linear memory through its exported `__ronto_alloc` and decodes a returned
`(ptr, len)` pair by hand; [`wasm-browser`](../wasm-browser) and
[`hiragana`](../hiragana) ship an 8-function hand-written WASI shim; the
[`webgl-*`](../webgl-galaxy) demos hand-write a JavaScript import object of
dozens of host functions to satisfy their `rontolisp:wasm-import` declarations.
This page's entire interface to the module is:

```js
const { mandelbrot, julia, palette, escapeTime, inSet } = await import("./dist/fractal.js");
const chars = mandelbrot(centerX, centerY, scale, cols, rows, maxIter);
```

That is the difference the component model makes: [`wit/fractal.wit`](wit/fractal.wit)
types the exports, the canonical ABI moves the strings across the boundary and
frees them after every call, and `jco` generates the JavaScript bindings by
reading that world back out of the `.wasm`.

## Build

Needs the rontolisp jar (built once from the repository root) and Node, for
`npx`. Everything else `build.sh` downloads on demand.

```bash
# from the repository root
./mvnw clean package -DskipTests

# here
./build.sh
```

`build.sh` runs two commands:

```bash
rontolisp fractal.lisp -o fractal.wasm --no-gc --component --optimize --emit-wit
npx -y @bytecodealliance/jco transpile fractal.wasm -o dist --base64-cutoff 1000000
```

The component is ~2.5 KB. `--base64-cutoff` inlines the core module into the
generated JavaScript, so `dist/fractal.js` is a single self-contained ES module
(~98 KB) with **zero `import` statements** -- the page fetches nothing but that
file.

## Run

ES modules need an http server (`file://` will not load them):

```bash
python3 -m http.server 8000
# open http://localhost:8000/
```

Hover the Mandelbrot set on the left and the Julia set of the point under the
cursor is drawn on the right, live; click to zoom in, shift-click to zoom out.
A 240x144 frame is one call and renders in ~20 ms in Chrome.

## What the page has to supply: nothing

The world has **no imports**, so there is nothing for the page to provide. That
is not a JavaScript convenience -- it is a property of the module: `--no-gc`
compiles a pure-compute reactor with no WASI, no GC and no runtime flags, and a
component with an empty import section instantiates against an empty world.

Two shapes in `fractal.lisp` are dictated by the boundary rather than by the
mathematics, and both are worth knowing before writing a world of your own:

- **A frame comes back as a string of palette characters, one per pixel.** A
  component that could return a `list<u8>` would not need the detour, but a
  rontolisp component's exports carry scalars and strings only: `string` is the
  widest channel available today. The module exports its own `palette`,
  so the encoding is declared in the world and hard-coded nowhere in the page.
- **Mandelbrot and Julia are separate exports** although one iteration loop
  serves both: a wasm-GC callable takes at most **seven parameters**, so a single
  `render(center-x, center-y, scale, cols, rows, max-iter, julia, cx, cy)` would
  compile under `--no-gc` and fail on the wasm-GC backend. Six parameters each
  keeps one source compiling on every backend -- which is what `examples.yaml`
  pins (`no-gc` and `wasm-component`).

## What does NOT work yet

This demo is `--no-gc` on purpose. A **wasm-GC** `--component` also loads and
computes in Chrome 149 -- wasm-GC, JSPI and the canonical ABI are all fine there
-- but it cannot **print** in a browser, for two upstream reasons that are worth
stating separately:

- **jco 1.25.2 half-emits the `future` runtime.** A printing component imports
  `wasi:cli/stdout`, whose `write-via-stream` returns a `future<...>` in WASI
  0.3. The transpiled bundle *references* `FutureReadableEnd`, `FutureEnd` and
  `FutureWritableEnd` and **defines none of them**, so the first write dies with
  `ReferenceError: FutureReadableEnd is not defined` -- on the import side,
  before any export is even lifted. It reproduces under every `--async-mode` /
  `--instantiation` combination. (This is distinct from the known gap where jco
  cannot *call* a stackful-async export at all.)
- **`@bytecodealliance/preview3-shim` has no browser build.** Its `exports` map
  has only a `node` condition and its code imports `node:worker_threads`, `net`,
  `fs/promises`, ... -- unlike `preview2-shim`, which ships `dist/browser/`. So a
  WASI 0.3 component in a browser needs a hand-written 0.3 shim (~90 lines, nine
  names jco destructures at module load).

Neither is a rontolisp bug, and neither can touch this demo: a non-printing
`--no-gc` component imports **nothing**, so there is no WASI, no `future` and no
shim in the picture. (A *printing* `--no-gc` component shares the wasm-GC
component's fate here since its print bridge moved to WASI 0.3: it imports
`wasi:cli/stdout@0.3.0` and its exports are async lifts, so keep browser-bound
programs print-free.) (Node is a *worse* host than Chrome here, incidentally:
Node 22.16 cannot import a wasm-GC component at all -- `TypeError:
WebAssembly.Suspending is not a constructor`.)

## The world is the contract

`rontolisp:wit-export` at the bottom of `fractal.lisp` names
[`wit/fractal.wit`](wit/fractal.wit). The compiler reads it, checks the five
exports it declares against the defuns -- name, arity, parameter and result types
-- and lowers each into the export directive it stands for. Rename a defun,
change an argument, return the wrong type, and the build fails with a compile
error naming the WIT line instead of a puzzle in the browser console. The check
runs on **every** backend, so even `rontolisp fractal.lisp` (the interpreter)
catches a drifted contract without compiling anything.

`--emit-wit` writes the component's own world next to the `.wasm` as
`fractal.wit`: the same five exports, under the package and world name a
component's type always has (`root:component` / `root`). It is the `.wit` without
the binary -- hand it to `wit-bindgen` or `jco types` and you get bindings for
any host language, with no `wasm-tools` introspection step.
