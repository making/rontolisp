# cube.lisp — hello 3D: a rotating cube, matrices and all, driven from Lisp

The middle step between [`webgl-triangle/`](../webgl-triangle) (the hello
world) and [`webgl-galaxy/`](../webgl-galaxy) (a full pipeline). This one adds
the parts every real 3D program needs — a vertex buffer, a depth test, and
4x4 matrix math — and keeps them all in Lisp: the perspective projection, the
rotation matrices and their products are computed in Lisp every frame.

**Live demo:** <https://making.github.io/rontolisp/webgl-cube/> (this
directory is published as a subpath of the GitHub Pages site by
`.github/workflows/pages.yaml`).

## What's in here

| File         | Purpose                                                          |
| ------------ | ---------------------------------------------------------------- |
| `cube.lisp`  | Everything: GLSL shaders, cube geometry, mat4 math, the frame.    |
| `index.html` | The host page: one-line WebGL2 bindings + the animation loop.     |
| `cube.wasm`  | The compiled `--no-wasi` reactor (checked in, ~9 KB).             |
| `build.sh`   | Recompiles `cube.lisp` to `cube.wasm`.                            |

The WebGL2 API boundary itself (the WIT interface, the enum constants and the
shader helpers) lives in the shared `gl` package,
[`../webgl-common/gl.lisp`](../webgl-common), spliced in at compile time by
`(require :gl "../webgl-common/gl.lisp")`. The page's matching WebGL2 bindings
are generated from the same `gl.wit`, imported from
`../webgl-common/gl-imports.js`.

## How it works

Beyond the triangle's ten imports, the cube needs a uniform, a buffer,
attributes and a depth test — still one line of JavaScript each. Bulk floats
are the interesting part: neither the 216 floats
of cube geometry nor the 16 of the model-view-projection matrix can cross the
boundary as one WASM value, so the page keeps a small staging `Float32Array`
and three non-WebGL imports move data through it:

```lisp
(rontolisp:wasm-import 'set-float :from "gl" :as "setFloat"
                       :params '(:int :float) :returns :void)
(rontolisp:wasm-import 'gl-buffer-data-floats :from "gl" :as "bufferDataFloats"
                       :params '(:int :int :int) :returns :void)
(rontolisp:wasm-import 'gl-uniform-matrix4fv :from "gl" :as "uniformMatrix4fv"
                       :params '(:int) :returns :void)
```

```js
setFloat: (i, v) => { staging[i] = v; },
bufferDataFloats: (target, count, usage) =>
  gl.bufferData(target, staging.subarray(0, count), usage),
uniformMatrix4fv: (loc) =>
  gl.uniformMatrix4fv(handles[loc], false, staging.subarray(0, 16)),
```

The Lisp side owns everything that means anything:

- **Geometry.** The cube is eight corner lists and six `(quad-indices color)`
  face lists; `push-face` walks them and emits 36 interleaved vertices
  (position + color) through `set-float`, uploaded once at setup.
- **Matrix math.** `mat4-mul`, `mat4-perspective`, `mat4-rotation-x/y` and
  `mat4-translation` operate on 16-element arrays in column-major order (the
  OpenGL convention). `tan` for the projection is `sin`/`cos`, computed in Lisp
  by the built-ins — `cube.wasm` imports no `math` module at all.
- **The frame.** Every tick, `frame` multiplies
  `projection * translation * rotation-y * rotation-x`, writes the result
  through `set-float`, points the `uMvp` uniform at it, clears color + depth
  and draws 36 vertices.

Setup runs at load time (the top-level `(setup-gl)` inside `_initialize()`),
so the page only instantiates the module, calls `_initialize()`, and drives
`frame(t)` from `requestAnimationFrame`.

## Building and running

```bash
# from the repo root, once:
./mvnw clean package

# recompile the .wasm after editing cube.lisp:
examples/browser/webgl-cube/build.sh

# serve and open (any static file server works). The page imports the generated
# ../webgl-common/gl-imports.js, so serve examples/browser, not this directory:
jwebserver -p 8000 --directory "$PWD/examples/browser"
open http://localhost:8000/webgl-cube/
```

The page needs a browser with WebAssembly GC support (Chrome 119+,
Firefox 120+, Safari 18.2+, Edge 119+).

## Notes

- The module is compiled with `--no-wasi`, so its *only* imports are the
  host functions the program reaches — the import object is the whole
  embedding API. With `--optimize` the shipped `cube.wasm` is about 9 KB and
  imports 26 functions: 20 of the shared `gl` package's 29 WebGL2 entries, its
  `ui.fail`, the three staging entries above and two canvas metrics. The nine
  `gl` entries the cube never calls (`uniform3f`, the VAO pair, `viewport`, ...)
  are tree-shaken away.
- Shader compile/link errors are reported by the shared `gl:build-program`
  (`gl:get-shader-parameter` / `gl:get-shader-info-log`, whose info log crosses
  back as a `string` result, shown through the `fail` entry of `gl.wit`'s `ui`
  interface).
- On the interpreter and JVM backends the `rontolisp:wasm-import` directives
  define stubs that signal an error when called, and the shared `gl` package's
  WIT-imported entries dispatch through a provider nothing binds (signaling
  `rontolisp:wit-error`), so this program is WASM-only by nature (there is no
  host to draw with elsewhere).
