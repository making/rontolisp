# galaxy.lisp — a spiral galaxy: the WebGL pipeline driven from Lisp

The `rontolisp:wasm-import` showcase: a Lisp program that *calls into the
browser*. Not just the physics — the whole WebGL2 pipeline runs from Lisp
compiled to WebAssembly. The GLSL shader sources live in the Lisp file as string
constants; Lisp compiles and links them, sets up the vertex buffer, attributes
and blending, and issues every clear and draw call. JavaScript supplies the
WebGL2 API as one-line bindings generated from the shared `gl.wit`, plus the page
UI — no rendering logic of its own.

**Live demo:** <https://making.github.io/rontolisp/webgl-galaxy/>

## What's in here

| File          | Purpose                                                            |
| ------------- | ------------------------------------------------------------------ |
| `galaxy.lisp` | Everything: GLSL shaders, pipeline setup, orbits, per-star drawing. |
| `index.html`  | The host page: one-line WebGL2 bindings + the HUD.                 |
| `galaxy.wasm` | The compiled `--no-wasi` reactor (checked in).                      |
| `build.sh`    | Recompiles `galaxy.lisp` to `galaxy.wasm`.                         |

The WebGL2 API boundary itself (the WIT interface, the enum constants and the
shader helpers) lives in the shared `gl` package,
[`../webgl-common/gl.lisp`](../webgl-common), spliced in at compile time by
`(require :gl "../webgl-common/gl.lisp")`.

## How the boundary works

`galaxy.lisp` declares 7 host functions of its own — the staging pair, the
canvas metrics, and two `math` entries that no longer reach the module (see the
notes):

```lisp
(rontolisp:wasm-import 'set-vertex :from "gl" :as "setVertex"
                       :params '(:int :float :float :float :float) :returns :void)
(rontolisp:wasm-import 'canvas-width :from "canvas" :as "width"
                       :params '() :returns :float)
;; ... bufferSubData, canvas height, devicePixelRatio, sin, cos
```

The literal WebGL2 entries are not declared here at all: they come from the
shared `gl` package, which binds them (and the `fail` error reporter) from
`../webgl-common/gl.wit` with two `rontolisp:wit-import` directives. The page's
matching import object is generated from that same file, so the JavaScript side
is a spread plus this demo's own staging one-liners over a handle table:

```js
import { glImports, uiImports } from "../webgl-common/gl-imports.js";

const imports = {
  gl: {
    ...glImports({ gl: gl2, handles, addHandle, str, retStr }),
    bufferSubData: (target, off, count) => gl2.bufferSubData(target, off, staging, 0, count),
    setVertex: (i, x, y, hue, size) => { /* fill the staging array */ },
  },
  ui: uiImports({ ui, str }),
  canvas: { width: () => canvas.width, /* ... */ },
};
```

Every type crosses the boundary somewhere in this example — spelled with the
`:int`/`:float`/`:bool`/`:string` designators in this demo's own directives, and
as `s32`/`f32`/`bool`/`string` in `gl.wit` for the shared entries:

- `:int` — GL enums, sizes, and the handles for shaders/programs/buffers.
- `:float` — star positions, uniforms, canvas metrics.
- `:bool` — `vertexAttribPointer`'s `normalized` flag, compile/link status.
- `:string` *parameter* — the GLSL source and uniform names travel from Lisp
  to `shaderSource`/`getUniformLocation` as `(ptr,len)` into the module's
  exported linear memory.
- `:string` *result* — on a shader compile error, `getShaderInfoLog` writes
  the log back through the exported `__ronto_alloc` allocator and the shared
  `gl:make-shader` hands it to the imported `fail` to show the page's error
  box.

Startup order is simple: the page creates the WebGL2 context, instantiates the
module, and calls `_initialize()`, which runs the top-level `(setup-gl)` — so the
shaders compile and the pipeline is configured *from Lisp* before the first
frame. Each `requestAnimationFrame` tick then calls `exports.frame(t)`: Lisp
sets the viewport, clears, computes every star's position on its slowly
precessing ellipse, stages it, uploads and draws. At 16,000 stars and 60 fps
that is several million Lisp-to-JavaScript calls per second — the counter in the
corner keeps score.

The two staging imports (`setVertex`, `bufferSubData`) are the only ones that
are not literal WebGL2 entries: per-star floats cannot cross into GPU memory
one call at a time, so the page keeps a single `Float32Array` that Lisp fills
and uploads. That array and the handle table are the host's entire state.

There is no randomness: a `--no-wasi` reactor has no entropy source, so the
stars are scattered with low-discrepancy sequences (the golden angle for orbit
phase, `sqrt 2 - 1` for radius). One subtlety worth stealing: the radius
sequence must *not* be built on the golden ratio, because the golden angle is
`2*pi*(1-phi)` — the two sequences would be exactly correlated and every star
would land on a single curve.

## Building and running

```bash
# from the repo root, once:
./mvnw clean package

# recompile the .wasm after editing galaxy.lisp:
examples/browser/webgl-galaxy/build.sh

# serve and open (any static file server works). The page imports the generated
# ../webgl-common/gl-imports.js, so serve examples/browser, not this directory:
jwebserver -p 8000 --directory "$PWD/examples/browser"
open http://localhost:8000/webgl-galaxy/
```

The page needs a browser with WebAssembly GC support (Chrome 119+,
Firefox 120+, Safari 18.2+, Edge 119+).

## Notes

- The module is compiled with `--no-wasi`, so its *only* imports are host
  functions — the import object is the whole embedding API. The shipped
  `galaxy.wasm` imports 32 of them: 26 of the shared `gl` package's 29 WebGL2
  entries, its `ui.fail`, and 5 of the 7 `galaxy.lisp` declares. (`math.sin` and
  `math.cos` are not among them — the WASM backend compiles `sin`/`cos`
  natively, so those two declarations are unreferenced and `--optimize` drops
  them.)
- `--optimize` tree-shakes the runtime down to a few KB, of which the GLSL
  sources are about a third.
- On the interpreter and JVM backends the `rontolisp:wasm-import` directives
  define stubs that signal an error when called, and the shared `gl` package's
  WIT-imported entries dispatch through a provider nothing binds (signaling
  `rontolisp:wit-error`), so this program is WASM-only by nature (there is no
  host to draw with elsewhere).
