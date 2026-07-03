# heat3d.lisp — a rank-3 array diffusing heat, drawn by WebGL

This example is the rank-3 array showcase in the browser: the whole state of
the page is **one rank-3 `(n n n)` `make-array`**. Every frame, Lisp deposits
heat at two orbiting sources with three-subscript `(setf (aref grid i j k))`,
runs one explicit diffusion step over the lattice (insulated walls, mild
global cooling), normalizes the colors with the rank-generic
`(linalg:amax grid)`, reports `(linalg:sum grid)` to the HUD, and projects
every voxel to a screen-space point itself. JavaScript is the same host
boundary as [`webgl-galaxy`](../webgl-galaxy): one-line WebGL2 bindings over a
handle table, plus the HUD — no simulation or rendering logic of its own.

It is the browser companion of [`examples/heat3d.lisp`](../heat3d.lisp), the
console version whose exact rational arithmetic conserves the total heat as
*exactly* 1000. Here the voxels hold floats instead: the simulation runs
forever, and exact ratio denominators would grow without bound (and overflow
the WASM backend's i31 fixnums within a few steps).

**Live demo:** <https://making.github.io/rontolisp/webgl-heat3d/> (this
directory is published as a subpath of the GitHub Pages site by
`.github/workflows/pages.yaml`).

## What's in here

| File          | Purpose                                                                 |
| ------------- | ----------------------------------------------------------------------- |
| `heat3d.lisp` | Everything: the rank-3 simulation, GLSL shaders, projection, draw calls. |
| `index.html`  | The host page: one-line WebGL2 bindings + the HUD.                       |
| `heat3d.wasm` | The compiled `--no-wasi` reactor (checked in).                           |
| `build.sh`    | Recompiles `heat3d.lisp` to `heat3d.wasm`.                               |

The WebGL2 API boundary itself (the imports, the enum constants and the
shader helpers) lives in the shared `gl` package,
[`../webgl-common/gl.lisp`](../webgl-common), spliced in at compile time by
`(require :gl "../webgl-common/gl.lisp")`.

## The rank-3 array is the program

Everything interesting happens against one array (and its double buffer):

```lisp
(setq *grid* (make-array (list n n n) :initial-element 0.0))

;; three-subscript reads and writes, all six lattice neighbours:
(setf (aref out i j k) (* +cool+ (+ c (* +alpha+ acc))))

;; rank-generic linalg reductions over the whole rank-3 grid, every frame:
(linalg:amax *grid*)   ; normalizes the color scale
(linalg:sum *grid*)    ; the HUD's "total heat" meter
```

The flat row-major data order is also exactly the order the voxels are
written into the vertex buffer, so vertex `v` is the voxel at
`(array-row-major-index grid i j k) = v`.

The rest is the same shape as `webgl-galaxy`: the GLSL shader sources live in
the Lisp file, Lisp compiles and links them at `_initialize` time, and each
`requestAnimationFrame` tick calls `exports.frame(t)` — Lisp injects,
diffuses, rotates and perspective-projects every voxel, stages it with
`set-vertex`, uploads with `gl-buffer-sub-data` and draws with
`gl:draw-arrays` (additive blending, so no depth sorting). The HUD's "total
heat" meter polls the exported `totalHeat` (a `rontolisp:wasm-export ... :as`
alias for `total-heat`). At the 20×20×20 setting that is 8,000 voxels
simulated and projected in Lisp per frame.

## Building and running

```bash
# from the repo root, once:
./mvnw clean package

# recompile the .wasm after editing heat3d.lisp:
examples/webgl-heat3d/build.sh

# serve and open (any static file server works):
jwebserver -p 8000 --directory "$PWD/examples/webgl-heat3d"
open http://localhost:8000/
```

The page needs a browser with WebAssembly GC support (Chrome 119+,
Firefox 120+, Safari 18.2+, Edge 119+).

## Notes

- The module is compiled with `--no-wasi`, so its *only* imports are the host
  functions declared in `heat3d.lisp` and the shared `gl` package — the
  import object is the whole embedding API. `--optimize` tree-shakes the
  runtime, including the unused parts of the spliced `linalg` library and
  the `gl` entries this demo never calls.
- The `.wasm` is larger than the galaxy's (~60 KB vs ~10 KB) because the
  array runtime and the reachable `linalg` definitions ship with it.
- On the interpreter and JVM backends the `rontolisp:wasm-import` directives
  define stubs that signal an error when called, so this program is
  WASM-only by nature (there is no host to draw with elsewhere) — run
  [`examples/heat3d.lisp`](../heat3d.lisp) for the cross-backend console
  version.
