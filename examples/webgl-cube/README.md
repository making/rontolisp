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

## How it works

Beyond the triangle's ten imports, the cube needs a uniform, a buffer,
attributes and a depth test — 23 host functions in total, still one line of
JavaScript each. Bulk floats are the interesting part: neither the 216 floats
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
  OpenGL convention). `tan` for the projection is `sin`/`cos` — both borrowed
  from JavaScript's `Math` via `wasm-import`.
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
examples/webgl-cube/build.sh

# serve and open (any static file server works):
jwebserver -p 8000 --directory "$PWD/examples/webgl-cube"
open http://localhost:8000/
```

The page needs a browser with WebAssembly GC support (Chrome 119+,
Firefox 120+, Safari 18.2+, Edge 119+).

## Notes

- The module is compiled with `--no-wasi`, so its *only* imports are the 23
  functions above — the import object is the whole embedding API. With
  `--optimize` the shipped `cube.wasm` is about 9 KB.
- For brevity there is no shader-compile error check; see
  [`webgl-galaxy/`](../webgl-galaxy) for the error-reporting pattern
  (`getShaderParameter` / `getShaderInfoLog` as a `:string` result).
- On the interpreter and JVM backends the `rontolisp:wasm-import` directives
  define stubs that signal an error when called, so this program is
  WASM-only by nature (there is no host to draw with elsewhere).
