# galaxy.lisp — a spiral galaxy: the WebGL pipeline driven from Lisp

This example is the `rontolisp:wasm-import` showcase: a Lisp program that
*calls into the browser*. Not just the physics — the whole WebGL2 pipeline
runs from Lisp compiled to WebAssembly. The GLSL shader sources live in the
Lisp file as string constants; Lisp compiles and links them, sets up the
vertex buffer, attributes and blending, and issues every clear and draw call.
JavaScript supplies the WebGL2 API as a table of one-line bindings (a handle
table maps `:int` handles to GL objects) plus the page UI — it contains no
rendering logic of its own.

**Live demo:** <https://making.github.io/rontolisp/webgl-galaxy/> (this
directory is published as a subpath of the GitHub Pages site by
`.github/workflows/pages.yaml`).

## What's in here

| File          | Purpose                                                            |
| ------------- | ------------------------------------------------------------------ |
| `galaxy.lisp` | Everything: GLSL shaders, pipeline setup, orbits, per-star drawing. |
| `index.html`  | The host page: one-line WebGL2 bindings + the HUD.                 |
| `galaxy.wasm` | The compiled `--no-wasi` reactor (checked in, ~10 KB).             |
| `build.sh`    | Recompiles `galaxy.lisp` to `galaxy.wasm`.                         |

The WebGL2 API boundary itself (the imports, the enum constants and the
shader helpers) lives in the shared `gl` package,
[`../webgl-common/gl.lisp`](../webgl-common), spliced in at compile time by
`(require :gl "../webgl-common/gl.lisp")`.

## How the boundary works

`galaxy.lisp` declares 34 host functions. Most are literal WebGL2 API
entries, imported one at a time in the shared `gl` package:

```lisp
(rontolisp:wasm-import 'create-shader :from "gl" :as "createShader"
                       :params '(:int) :returns :int)
(rontolisp:wasm-import 'shader-source :from "gl" :as "shaderSource"
                       :params '(:int :string) :returns :void)
(rontolisp:wasm-import 'draw-arrays :from "gl" :as "drawArrays"
                       :params '(:int :int :int) :returns :void)
;; ... plus canvas metrics, Math.sin / Math.cos, and an error reporter
```

and the JavaScript side is nothing but one-liners over a handle table:

```js
const handles = [];
const H = (obj) => handles.push(obj) - 1;

const imports = {
  gl: {
    createShader: (type) => H(gl2.createShader(type)),
    shaderSource: (sh, p, n) => gl2.shaderSource(handles[sh], str(p, n)),
    drawArrays: (mode, first, count) => gl2.drawArrays(mode, first, count),
    // ...
  },
  math: { sin: Math.sin, cos: Math.cos },
};
```

Every type designator crosses the boundary somewhere in this example:

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

Startup order matters and is pleasingly simple: the page creates the WebGL2
context, instantiates the module, and calls `_initialize()` — which runs the
top-level `(setup-gl)`, so the shaders compile and the pipeline is configured
*from Lisp* before the first frame. Each `requestAnimationFrame` tick then
calls `exports.frame(t)`: Lisp sets the viewport, clears, computes every
star's position on its slowly precessing ellipse, stages it with `set-vertex`,
uploads with `gl-buffer-sub-data` and draws with `gl:draw-arrays`. At 16,000
stars and 60 fps that is several million Lisp-to-JavaScript calls per second —
the counter in the corner keeps score.

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
examples/webgl-galaxy/build.sh

# serve and open (any static file server works):
jwebserver -p 8000 --directory "$PWD/examples/webgl-galaxy"
open http://localhost:8000/
```

The page needs a browser with WebAssembly GC support (Chrome 119+,
Firefox 120+, Safari 18.2+, Edge 119+).

## Notes

- The module is compiled with `--no-wasi`, so its *only* imports are the 34
  functions above — the import object is the whole embedding API.
- `--optimize` tree-shakes the runtime: the shipped `galaxy.wasm` is about
  10 KB (the GLSL sources account for a third of it).
- On the interpreter and JVM backends the `rontolisp:wasm-import` directives
  define stubs that signal an error when called, so this program is
  WASM-only by nature (there is no host to draw with elsewhere).
