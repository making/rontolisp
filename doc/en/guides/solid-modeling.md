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
| `(geom:revolution profile :sides 64)` | a profile turned about z, capped where it leaves the axis (a closed profile gets no cap) |
| `(geom:polyhedron points facets)` | raw points and index loops -- the escape hatch |
| `(geom:arrow :length 200 :radius 6)` | a shaft and a pointed head as one solid, along `:direction` |
| `(geom:triad :at (geom:vec3 0 0 0))` | three of those -- +x red, +y green, +z blue -- as a list |

`:sides` and `:stacks` are the tessellation. A tessellated primitive is
*inscribed* in its smooth ideal, so a measured volume converges on the closed
form **from below**:

```lisp
(round (geom:volume (geom:cylinder :radius 50 :height 100 :sides 64)))
; => 784137
```

against `pi r^2 h = 785398`, 0.16% low.

`geom:arrow` is the one that is not a shape from a geometry textbook, and it is
here rather than in the viewer for a reason. An origin indicator drawn as three
line segments cannot be given a thickness -- a line primitive has no width --
and cannot be tipped. An arrow that is a solid can be both, and it also gets
bounds, a volume, a place on a kinematic chain, the CSG booleans, all four
backends and the browser renderer without a line of renderer code. Its tail is
the model origin and its tip is `:length` along `:direction`; every measurement
left unstated is a fraction of the length, so `(geom:arrow :length 200)` is one
call. `geom:triad` is three of them in the conventional tints, as a list of
solids the caller owns:

```lisp
(mapcar #'geom:label-of (geom:triad :at (geom:vec3 0 0 0)))
; => ("x" "y" "z")
```

Unlike the tessellated primitives, its volume is *exact* against the closed form
of the shape actually built -- a prism plus a pyramid on the same n-gon -- which
is what pins its winding on every backend.

## Reading a model file

A mesh someone else authored is the ordinary way a solid this big enters a
program, so `geom` reads one:

| Reader | What it reads |
|---|---|
| `(geom:read-obj "bunny.obj")` | Wavefront OBJ: `v` lines and `f` lines, any facet size, `v/vt/vn` tokens and negative indices |
| `(geom:read-stl "part.stl")` | STL, either dialect -- which one is decided from the file's own shape |
| `(geom:read-ply "scan.ply")` | PLY, ASCII or binary little-endian, the properties taken from the header |
| `(geom:read-gltf "duck.glb")` | glTF 2.0, `.glb` or `.gltf` -- a SCENE, answered as a list of solids |
| `(geom:read-model "whatever")` | the format sniffed from the file's bytes; `:format` says it outright |

They answer an ordinary `geom:solid`, so everything above applies to it
unchanged -- `geom:volume`, `geom:bounds`, the booleans, a viewer. They take
`:color` and `:label` like every constructor beside them. The one exception in
shape is glTF, which is a scene rather than a mesh: `geom:read-gltf` answers
the **list** of solids its nodes pose -- `scene:add` splices a list, and each
solid's `geom:world-transform` carries its node hierarchy, so a multi-part
model's parts land where its nodes say. A node's scale is baked into the
vertices at read time (the transform stays rigid), so the measurements see it.

```console
CL-USER> (defvar *bunny* (geom:read-obj "bunny.obj" :color (geom:vec3 0.85 0.72 0.5)))
CL-USER> (geom:mesh-triangle-count *bunny*)
69451
CL-USER> (defvar *v* (scene:viewer))
CL-USER> (scene:grid *v* :extent nil)
CL-USER> (scene:shading *v* :solid)
CL-USER> (scene:add *v* *bunny*)
CL-USER> (scene:fit *v*)
```

Three things a real file teaches, none of them a bug:

- **A file carries its own units.** That bunny is 0.2 across, in metres; a
  printable part is 200, in millimetres. `scene:fit` frames either, and
  `(scene:grid v :extent nil)` is usually what you want beside a small one --
  the grid's own default extent is 600 in `geom`'s unitless world.
- **`:solid` is the shading a dense mesh wants.** The default `:both` draws the
  wireframe over the triangles, which on 69,451 of them is a dark stipple.
- **Winding is the file's, and `geom:volume` is the test.** A negative volume
  means the mesh is wound clockwise seen from outside, which is the one thing
  the readers cannot fix for you.

What no reader keeps: materials beyond one colour, texture coordinates,
per-vertex normals and per-vertex colours. A `geom:solid` has one colour and its
facet normals are Newell's, computed from the geometry, so those records are
read past rather than half-kept.

## A scene graph

`geom:attach` hangs one node off another; `geom:detach` takes it back out. The
mutators are `geom:translate`, `geom:rotate`, `geom:place` and `geom:reorient`, and
each takes a named `:frame` rather than a positional flag -- `:local` (the
node's own axes, the default) or `:parent` (the axes it is attached to). A call
site reading `:frame :parent` needs no manual.

`:local` reads the offset or the axis in the orientation the node is CURRENTLY
in: on a node already turned a quarter turn about `z`,
`(geom:translate n (geom:vec3 10 0 0))` carries it along world `+y`, not `+x`.
That is what a walk-forward step wants; placing a node in world coordinates is
`:frame :parent`.

```lisp
(let* ((base (geom:make-node))
       (joint (geom:make-node :translation (geom:vec3 0 0 100) :parent base))
       (link (geom:cylinder :radius 8 :height 80)))
  (geom:attach joint link)
  (geom:rotate joint (/ 3.141592653589793 2) :y)
  (geom:translate base (geom:vec3 0 0 500))
  (mapcar (lambda (x) (round x)) (coerce (geom:world-translation link) 'list)))
; => (0 0 600)
```

`geom:translate` and `geom:rotate` accumulate; `geom:place` sets the pose
outright. An animation loop wants `geom:place`, because repeated `geom:rotate`
deltas drift.

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

Scaling rewrites the model vertices -- it changes the *part*, where the pose
mutators change only a node's placement -- so it follows CL's own
functional/destructive convention (`reverse`/`nreverse`, `union`/`nunion`):
`geom:scale` builds a **new** solid like the booleans, recording
`(:scale s factor)` in its history, and `geom:nscale` rewrites the solid in
place -- the one vertex mutation the package offers, and therefore the one
place that drops both caches and `geom:user-data`. The factor is a number, or
a 3-vector or list for a non-uniform scale; a mirroring factor flips the
facets so the winding stays outward, and a zero component is refused.

```lisp
(let* ((s (geom:box 10))
       (c (geom:scale s '(1 2 3))))
  (list (geom:volume c) (geom:volume s) (geom:volume (geom:nscale s 2))))
; => (6000.0 1000.0 8000.0)
```

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
  (geom:translate b (geom:vec3 100 0 0))
  (coerce (geom:bounds-center (geom:bounds b)) 'list))
; => (100.0 0.0 0.0)
```

## Booleans

`geom:union`, `geom:difference` and `geom:intersection` are what turn scenery
into parts: a plate with four bolt holes, a block with a slot milled in it, a
housing that is the outside minus the inside. Each takes its operands in
**world** coordinates -- `(geom:difference plate hole)` means what it looks
like after both have been placed -- leaves them untouched, and answers a new
root solid whose vertices are world coordinates. Volume is the oracle: for any
pair, `vol(A ∪ B) + vol(A ∩ B) = vol(A) + vol(B)` within the tessellation
error the primitives already carry.

```lisp
(let ((plate (geom:box '(100 100 20)))
      (hole (geom:cylinder :radius 10 :height 20 :sides 24)))
  (geom:translate hole (geom:vec3 0 0 -10))
  (round (geom:volume (geom:difference plate hole))))
; => 193788
```

The hole is exactly as deep as the plate is thick, and goes all the way
through: coplanar faces, a vertex or an edge lying exactly on a face, and two
solids exactly touching are handled cases. Disjoint solids intersect to an
**empty** solid (no facets, volume `0.0`) rather than an error, and a result
records what built it -- `(geom:history result)` answers `(op a b)` with the
untouched operands, so a program can re-run a model at a different parameter.

The pipeline is BSP clipping, run in float64 and narrowed back to float32 only
in the result's vertex array. Its classification tolerance is
`geom:*tolerance*` (default `1.0e-5`), **relative** to the operands' combined
bounding box -- `geom` has no unit of length, so an absolute epsilon could not
be right for both a 0.001-scale and a 1000-scale model. Rebind it around a
call to loosen or tighten one operation.

`geom:section` is the same classification with one operand trivial: the loops
where a plane cuts a solid, each a rank-2 `(n 3)` packed array of world
points -- a cross-section drawing in one call.

```lisp
(length (geom:section (geom:torus :radius 60 :tube 20 :sides 24 :rings 12)))
; => 2
```

The equator cuts the tube twice: the boundary and the hole. Outer loops are
wound counter-clockwise seen from the normal's positive side, holes clockwise.

## Seeing it: the `scene` viewer

`geom` draws nothing -- it runs on every backend, and most of them have no
screen. On **macOS** the `scene` package is the other half: a window with a
Metal surface on it, an orbit/pan/dolly camera, a ground grid and axis triads.
It ships inside the interpreter the way `geom` does, so a bare REPL is three
lines away from a picture.

```console
CL-USER> (defvar *v* (scene:viewer :title "arm" :width 900 :height 640))
CL-USER> (scene:add *v* (geom:cylinder :radius 60 :height 140))
CL-USER> (scene:fit *v*)
CL-USER> (scene:refresh *v*)
```

Drag to orbit, shift-drag to pan, scroll to dolly, resize the window. The camera
gestures redraw by themselves; the mutators do not, because a loop adding sixty
solids must not draw sixty frames -- the step after a batch of them is
`scene:refresh`, or `scene:animate` for a scene that moves. A viewer is a CLOS
instance rather than a set of globals, so two windows can exist in one image and
orbit independently.

**This is what the cached mesh above is for.** Each solid's model-space mesh
goes into a GPU buffer of its own the first time it is drawn -- kept in that
solid's `geom:user-data` -- and a frame sets one 4x4 model matrix and one colour
per solid and issues one draw call. Nothing in Lisp touches a triangle during a
frame, which is the difference between 9.0 ms and 380 ms on the model measured
above. So a joint that moves costs one matrix, and `scene:animate`'s hook is
free to re-pose the whole chain every frame.

`scene:shading` picks `:solid`, `:wireframe` or `:both`; `scene:axes` picks
`nil` (the default -- nothing), `:world`, `:bodies` (each solid's OWN frame,
which is what makes a kinematic chain readable) or `:both`. Those are the
viewer's own furniture: line triads with no thickness, the world one scaled by
the view distance so it stays legible at any zoom. An origin indicator that is
an OBJECT -- placed where you say, with a shaft thickness and a pointed tip --
is `(geom:triad)` above, three solids added like any other -- `scene:add`
splices a list argument, so `(scene:add *v* (geom:triad))` is one call -- which
is why a viewer draws no triad unless it was asked for. `scene:add` also refuses
anything that is not a solid, naming it, rather than letting the draw callback
discover it a frame later. `scene:window-of` and `scene:context-of` are
the escape hatches to `appkit:` and to the `metal` drawing surface underneath.

`examples/macos/scene-solids.lisp` is every primitive on a shelf and
`examples/macos/scene-robot-arm.lisp` a four-joint arm solving its own inverse
kinematics onto a moving target -- the same machine as
`examples/macos/metal-robot-arm.lisp`, which builds its geometry by hand, and
the pair is worth reading together. Neither is in `examples.yaml`: they need a
display. Both `scene` and `metal` are macOS only, and a `.wasm` output for a
program that references either is refused by name, exactly as an `objc:` program
is.

### A viewer with no window

`scene:offscreen` is the same viewer drawing into a texture instead of a
drawable, and `scene:snapshot` hands back its pixels -- `width * height * 4`
bytes, BGRA, row 0 at the top. It is the same render function, not a second one
that resembles it, which is what lets a picture be checked: a red box is red in
the middle of the frame, a solid behind another is occluded, a facet wound the
wrong way is culled and `scene:fit` keeps the whole bounding box inside the
frame. `metal:offscreen` and `metal:pixels` are the rung underneath, for a
`metal:` program with no `geom` in it.

```console
CL-USER> (defvar *v* (scene:offscreen :width 320 :height 240))
CL-USER> (scene:add *v* (geom:box 200 :color (geom:vec3 1.0 0.2 0.2)))
CL-USER> (scene:fit *v*)
CL-USER> (length (scene:snapshot *v*))
307200
```

### Seeing it anywhere: the browser twin

`geom` runs wherever rontolisp does, and so can a renderer for it:
`examples/browser/webgl-solids/` is `scene`'s design ported to WebGL2 --
one vertex buffer per solid uploaded once, a per-draw model matrix uniform, one
draw call a solid, and `geom:mesh` and `geom:world-transform` consumed
unchanged. The only real difference is the projection: OpenGL's clip space puts
z in [-1, 1] where Metal's puts it in [0, 1]. There is deliberately no second
modelling layer in it -- that is what would make `geom` grow a browser dialect.

## What is not here

Convex hulls, offsetting, filleting, mesh repair, vertex welding, and anything
that draws -- drawing is `scene`'s half, above, and it is a consumer of this
package rather than a part of it. Of the mesh file formats, OBJ and STL are
read ("Reading a model file", above) and PLY and glTF are recognized but not
read yet; no format is WRITTEN. A solid whose facets came from a file is just a
`geom:polyhedron`.
