# solids.lisp — the `geom` solid modeller, drawn with WebGL

`geom` is rontolisp's solid modeller, shipped inside the interpreter the way `linalg` is:
rigid transforms, a scene graph, boundary-represented solids with a cached triangle mesh,
eight primitive constructors and the CSG booleans. It reaches for nothing but `linalg`, so
it runs identically on the interpreter, the JVM and both WASM backends — see the
[solid modeling guide](../../../doc/en/guides/solid-modeling.md).

Its shipped *renderer*, `scene`, is macOS only: it bottoms out in Metal through `objc:`.
This page is the other renderer. Same model, same design, same measurements — Metal there
and WebGL here — which is what the modelling package being backend-independent buys.

**Live demo:** <https://making.github.io/rontolisp/webgl-solids/>
(the directory is published as a Pages subpath by `.github/workflows/pages.yaml`.)

## What's in here

| File | Purpose |
|---|---|
| `solids.lisp` | The whole program: the geom model, the camera, the two pipelines and the frame |
| `index.html` | The page — a canvas, the import object, the pointer gestures and the frame loop |
| `solids.wasm` | The compiled reactor (checked in, so the demo needs no build to run) |
| `build.sh` | Recompiles `solids.wasm` |

The WebGL2 boundary comes from `../webgl-common/gl.lisp` through `(require :gl ...)`, the
same shared `gl` package every demo here splices in; the page's JavaScript bindings are
generated from the same `gl.wit`.

## How it works

**There is no modeling code in this program.** Every shape is a geom call:

```lisp
(geom:box '(900.0 900.0 40.0) :color (geom:vec3 0.30 0.33 0.42))
(geom:sphere :radius 95.0 :sides 24 :stacks 16 ...)
(geom:torus :radius 85.0 :tube 24.0 :sides 32 :rings 16 ...)
(geom:cone :radius 80.0 :height 240.0 :sides 32 ...)
(geom:triad :length 260.0 :radius 10.0 :at (geom:vec3 0.0 0.0 0.0))
```

The origin indicator is three of those calls: `geom:triad` answers three `geom:arrow`
solids — a shaft and a pointed head, with a thickness — and the renderer here knows
nothing about arrows, so a primitive added to the modeller reaches this page for free.

and the orange block is a **boundary-representation CSG subtraction** — three cylinders
bored through a box with `geom:difference`, whose BSP-clipping pipeline is the same one
the interpreter and the JVM run:

```lisp
(geom:difference (geom:difference (geom:difference block (bore -110.0))
                                  (bore 0.0))
                 (bore 110.0))
```

A second modelling layer written for the browser is exactly what this demo must not be:
it consumes `geom:mesh` and `geom:world-transform` unchanged, so the two renderers cannot
drift and `geom` cannot grow a browser dialect.

**No triangle is touched per frame.** A rigid solid's triangles never change; only its
pose does. So each solid's model-space mesh — `geom:mesh`, 18 floats a triangle
(position + normal, fan-triangulated with a Newell normal) — goes into a vertex buffer and
a VAO of its own the first time it is drawn, cached in `geom:user-data`, the slot the
package provides for a consumer's own state. A frame then costs one 4×4 matrix and one
`drawArrays` per solid. Re-transforming the vertices every frame instead measures 380 ms
against 9.0 on a 60-solid model (`.kb/geom.md`), which is why the vertex shader takes `uVP`
and `uModel` as *separate* uniforms and transforms the normal by `uModel` too.

**The staging imports.** Bulk floats cannot cross the WASM boundary one value at a time,
so the page keeps one `Float32Array` and Lisp fills it:

```lisp
(rontolisp:wasm-import 'set-float
                       :from "gl" :as "setFloat"
                       :params '(:int :float) :returns :void)
(rontolisp:wasm-import 'gl-buffer-data-floats
                       :from "gl" :as "bufferDataFloats"
                       :params '(:int :int :int) :returns :void)
(rontolisp:wasm-import 'gl-uniform-matrix4fv
                       :from "gl" :as "uniformMatrix4fv"
                       :params '(:int) :returns :void)
```

```js
setFloat: (i, v) => { staging[i] = v; },
bufferDataFloats: (target, count, usage) =>
  gl.bufferData(target, staging.subarray(0, count), usage),
uniformMatrix4fv: (loc) =>
  gl.uniformMatrix4fv(handles[loc], false, staging.subarray(0, 16)),
```

A mesh crosses once, at startup; a matrix crosses sixteen floats at a time, per solid per
frame.

**Matrix math** is `linalg`, exactly as `scene.lisp` does it: the look-at is
`[R | -R·eye]` over two `linalg:concatenate`s and a `linalg:matmul`, and a node's world
transform comes from `geom:world-transform`. The **one** difference from the Metal
renderer is the projection — OpenGL's clip space puts z in [-1, 1] where Metal's puts it
in [0, 1], so the third row of `perspective` differs and nothing else does.

**Winding and culling.** geom winds every facet counter-clockwise seen from outside, which
is what GL calls front-facing by default, so `(gl:enable +cull-face+)` is the whole of it
and half the triangles of a closed solid never reach the rasterizer. Metal decides facing
the same way, which is why `scene.lisp` sets `metal:+winding-counter-clockwise+` — and the
macOS test that renders offscreen asserts a facet wound the other way disappears.

**The camera** lives in Lisp: the page classifies the gesture and calls the exported
`orbit` and `zoom`, the way `../webgl-robot-arm/` does. The starting distance is
`scene:fit`'s arithmetic over `geom:bounds`, so no camera position is hand-tuned.

## Building and running

```bash
./mvnw clean package                       # from the repo root, once
examples/browser/webgl-solids/build.sh     # recompile solids.wasm
jwebserver -p 8000 --directory "$PWD/examples/browser"
open http://localhost:8000/webgl-solids/
```

The served root is `examples/browser`, because the page imports
`../webgl-common/gl-imports.js`. A browser with WebAssembly GC is required (Chrome 119+,
Firefox 120+, Safari 18.2+, Edge 119+).

## Notes

- `solids.wasm` imports 27 host functions under `--optimize`: the WebGL2 entries the
  program actually reaches, the three staging entries above and `canvas.width` /
  `canvas.height`. The page spreads the whole generated `glImports` union rather than
  picking fields, so an entry the tree-shaker keeps reachable through the funcall
  dispatcher is still provided.
- There is no `math` module. `sin`, `cos` and `tan` are rontolisp built-ins, so the
  projection and the orbit are computed in Lisp with nothing imported for them.
- On the interpreter and JVM backends the `rontolisp:wasm-import` directives define stubs
  that signal an error when called, so this program is WASM-only by nature. The geom half
  of it is not: the same model builds and measures on every backend.
- The macOS twin is `examples/macos/scene-solids.lisp`, over the shipped `scene` package.
