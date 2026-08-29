# Solid Modeling (geom)

The `geom` package builds solids you construct by name, hang off a kinematic
chain, move by transforms, and measure. It is written in rontolisp itself over
the [`linalg`](linear-algebra.md) kernels and nothing else -- no foreign calls,
no filesystem -- so it runs on the interpreter, in a compiled `.class`, in both
WASM backends and in the browser playground, and it loads on first use with
nothing to install or require:

```lisp
(geom:volume (geom:box '(100 200 300)))
; => 6000000.0
```

## Three types

`geom` has exactly three types, plus a bounding box.

A **`transform`** is a rigid motion: a translation 3-vector and a 3x3 rotation.
It is a **value** -- no parent, no identity, no cache -- and nothing mutates one
in place, so the same transform may be the local transform of any number of
nodes. `geom:compose`, `geom:invert`, `geom:transform-point` and
`geom:inverse-transform-point` all build new ones.

```lisp
(geom:transform-point (geom:make-transform :translation (geom:vec3 0 0 10))
                      (geom:vec3 1 2 3))
; => #f(1.0 2.0 13.0)
```

A **`node`** *has* a local transform rather than being one. That is why a solid,
a camera target and a bare joint frame are all nodes with no slot any of them
does not use. `geom:world-transform` composes a node's ancestors down to it and
memoizes the answer; every pose change drops the memo for the whole subtree.

A **`solid`** is a node carrying a boundary representation: `geom:vertices-of`
is a rank-2 `(n 3)` packed array of MODEL coordinates, and `geom:facets-of` is a
list of index loops, each wound counter-clockwise **seen from outside**. One
vertex array rather than a list of points is what makes a whole-solid transform
a single `linalg:matmul`.

Everything is float32 (`:element-type 'single-float`), because a packed
single-float array IS a GPU vertex buffer's bytes: a `geom` mesh reaches Metal
through `objc:data` with no conversion, and every `linalg` transform preserves
the width.

```lisp
(array-element-type (geom:mesh (geom:box 1)))
; => SINGLE-FLOAT
```

## Constructors

Noun constructors taking keywords. The one measurement that names the shape may
be positional; nothing else is.

| Constructor | What it builds |
|---|---|
| `(geom:box '(100 200 300))` | a rectangular solid centred on its origin (a scalar gives a cube) |
| `(geom:cylinder :radius 50 :height 100)` | a cylinder standing on z = 0 |
| `(geom:cone :radius 50 :height 120)` | a cone over a ring on z = 0; `:apex` makes it oblique |
| `(geom:sphere :radius 50 :sides 32 :stacks 24)` | a sphere centred on its origin |
| `(geom:torus :radius 60 :tube 20)` | a torus in the xy-plane |
| `(geom:extrusion profile :along 10)` | a closed profile swept along a vector -- the general prism |
| `(geom:revolution profile :sides 64)` | a profile turned about z, capped where it leaves the axis |
| `(geom:polyhedron points facets)` | raw points and index loops -- the escape hatch |

`:sides` and `:stacks` are the tessellation. A tessellated primitive is
*inscribed* in its smooth ideal, so a measured volume converges on the closed
form **from below**:

```lisp
(round (geom:volume (geom:cylinder :radius 50 :height 100 :sides 64)))
; => 784137
```

against `pi r^2 h = 785398`, 0.16% low.

## A scene graph

`geom:attach` hangs one node off another; `geom:detach` takes it back out. The
mutators are `geom:move`, `geom:turn`, `geom:place` and `geom:reorient`, and
each takes a named `:frame` rather than a positional flag -- `:local` (the
node's own axes, the default) or `:parent` (the axes it is attached to). A call
site reading `:frame :parent` needs no manual.

```lisp
(let* ((base (geom:make-node))
       (joint (geom:make-node :translation (geom:vec3 0 0 100) :parent base))
       (link (geom:cylinder :radius 8 :height 80)))
  (geom:attach joint link)
  (geom:turn joint (/ 3.141592653589793 2) :y)
  (geom:move base (geom:vec3 0 0 500))
  (mapcar (lambda (x) (round x)) (coerce (geom:world-translation link) 'list)))
; => (0 0 600)
```

`geom:move` accumulates; `geom:place` sets the pose outright. An animation loop
wants `geom:place`, because repeated `geom:turn` deltas drift.

## The mesh, and why it is cached

`geom:mesh` answers the solid's triangles in MODEL space: a packed
single-float array, 18 floats a triangle (three corners of position + normal),
fan-triangulated per facet with a Newell normal. It is computed once and kept on
the solid, and `geom:wireframe` (6 floats a segment, each edge once) likewise.

That cache is the load-bearing decision, not an optimization. A rigid solid's
triangles never change -- only its pose does. On a 60-solid articulated model of
13,800 triangles, a renderer that transforms every vertex into world space per
frame spends **380 ms a frame**; one that uploads the model-space mesh once and
hands the GPU the solid's world transform as a per-draw uniform spends **9.0
ms**. So `geom:mesh` is part of the public surface rather than a renderer's
internal detail, and `geom:user-data` is where a consumer keeps the GPU buffers
it built from it.

```lisp
(let ((s (geom:box 1)))
  (list (geom:mesh-triangle-count s) (eq (geom:mesh s) (geom:mesh s))))
; => (12 T)
```

`geom:scale` is the one vertex mutation the package offers, and therefore the
one place that drops both caches and `geom:user-data`.

## Measurements

`geom:bounds` answers the axis-aligned box of a solid, or of a list of them, in
**world** coordinates -- so it follows the scene graph. `geom:bounds-center`,
`geom:bounds-extent`, `geom:bounds-union`, `geom:lower-of` and `geom:upper-of`
read it.

`geom:volume` integrates the divergence theorem over the mesh triangles, which
makes it a **winding check** as well: a facet wound the wrong way subtracts, so
a mis-wound `geom:polyhedron` answers a grossly wrong number rather than a
slightly small one. `geom:centroid` is the same signed-tetrahedron sum, and
`geom:surface-area` the triangles' total area.

```lisp
(let ((b (geom:box 10)))
  (geom:move b (geom:vec3 100 0 0))
  (coerce (geom:bounds-center (geom:bounds b)) 'list))
; => (100.0 0.0 0.0)
```

## What is not here

Boolean operations (union, difference, intersection), mesh file formats, and
anything that draws. A solid whose facets came from an STL file is just a
`geom:polyhedron`.
