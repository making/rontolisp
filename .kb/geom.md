# The `geom` package (solid modeling)

One hand-written Lisp-source library,
`src/main/resources/am/ik/rontolisp/eval/geom.lisp`, following the `linalg.lisp` /
`appkit.lisp` pattern (`linalg.md`, `objc.md`) so a single implementation runs
identically on every backend: rigid `geom:transform` values, a scene-graph
`geom:node`, boundary-represented `geom:solid`s with a cached model-space triangle
mesh, eight primitive constructors, the measurements (`bounds`, `volume`,
`centroid`, `surface-area`), and the booleans (`union`, `difference`,
`intersection`, `section` -- see "Boolean operations" below). 55 exported
functions plus `geom:*tolerance*` and four CLOS class names.

**It reaches for nothing but `linalg`** -- no `objc:`, no `java:`, no filesystem, no
`SourceLoader`. That is the whole reason it ships rather than living in
`examples/macos/`: the same solids tessellate in the browser playground and in a
`.wasm`, and `scene` (the macOS viewer, "The renderer" below) is a CONSUMER of this
package, not a peer of it. Nothing may be added here that breaks that.

## Wiring (the `linalg` pair, exactly)

| piece | where |
|---|---|
| source | `src/main/resources/am/ik/rontolisp/eval/geom.lisp` |
| splice class | `eval/GeomLibrary` (`forms()` parsed once and cached, `process(program)`, `isGeomQualified`, `mentionsGeomClass`) |
| package | `PackageRegistry.GEOM_FUNCTIONS` + `LispNames.GEOM_PKG` + `geomFunctionNames()` + `BUILTIN_PACKAGE_NAMES` |
| interpreter | `LispEvaluator.ensureGeomLoaded()` on the first `geom:`-qualified FUNCTION resolution, plus `ensureGeomClassesFor(form)` at the seven `ensureAsdfClassesFor` sites |
| compile path | `cli/CompileFrontend`, `GeomLibrary.process` INSIDE `LinalgLibrary.process` (beside `TorchLibrary`) so the `linalg:` references in the spliced geom bodies pull linalg in too; the browser playground repeats the nesting |
| pruning | `LibraryDefunPruner.prunableNames()` collects `GeomLibrary.forms()` |
| tests | `eval/GeomLibraryTest` (interpreter), `ci-spec.yaml` cases `geom-solids-cross-backend`, `geom-transforms-cross-backend` and `geom-csg-cross-backend` (all four backends) |
| docs | `doc/{en,ja}/guides/solid-modeling.md`, 55 pages under `reference/functions/geom-*.md` |

**The class-mention trigger is not optional.** The interpreter's lazy load fires on
FUNCTION resolution, but a `defmethod` specializer, a `typep`, a `typecase` clause, a
`make-instance` or a `defclass` superclass can name `geom:solid` without calling any
geom function -- and would then see no such class and quietly answer `nil`. That is
what `GeomLibrary.mentionsGeomClass` / `LispEvaluator.ensureGeomClassesFor` exist for,
the `AsdfRuntimeLibrary.mentionsComponentClass` twin. A new geom class must be added to
`GeomLibrary.CLASS_NAMES`. The compile path needs no equivalent: its splice fires on
any `geom:` symbol anywhere, quoted data included.

**A library-internal helper may not be named `%make-<class>`.** `expandDefclass`
generates the constructor `make-instance` calls as `PKG::%make-<member>` for the class
`PKG:<member>`, so a hand-written `geom::%make-solid` SILENTLY replaced the constructor
of `geom:solid` and every `make-instance` then landed in the wrong lambda list
("Unknown keyword argument: :VERTICES"). The builder is `geom::%build-solid`.

## The type model, and why

- **`transform` is a VALUE, not a superclass.** A translation 3-vector and a 3x3
  rotation, both read-only slots; no parent, no identity, no cache. `compose` /
  `invert` / `transform-point` / `inverse-transform-point` build new ones, and so do
  the node mutators -- `move` / `turn` / `place` / `reorient` REPLACE `geom::%local`
  with a fresh transform rather than assigning into the old one. That is what makes it
  safe to hand one transform to several nodes, and it is a deliberate divergence from
  the spike (`.todo/563-solid-modeling-and-a-3d-viewer/geom.lisp`), which mutated in
  place and therefore aliased. The cost is one allocation per pose change, which is
  noise next to the 9.0 ms frame below.
- **`node` HAS a transform rather than being one.** Composition, not inheritance: a
  solid, a camera target and a bare joint frame are all nodes with no slot any of them
  does not use. `world-transform` is memoized in `geom::%world` and `geom::%stale`
  drops it down the WHOLE subtree on every pose change -- every slot of a node is
  internal (`geom::%local` / `%parent` / `%children` / `%world`) and the public
  readers are plain defuns, so there is no `setf` that could leave the memo stale.
- **`solid` is a node carrying a boundary representation.** `vertices` is ONE rank-2
  `(n 3)` packed array of MODEL coordinates, which is what makes a whole-solid
  transform a single `linalg:matmul` (`%solid-bounds` is the one place that does it);
  `facets` is a list of index loops.
- **`:frame` is a keyword, not a positional flag.** `(geom:move n v :frame :parent)`
  needs no manual; `:local` is the default.

## float32 everywhere

Every array the package builds is `:element-type 'single-float`, with the literal
spelled at each `make-array` so all four backends pick the `float[]` (TYPE_F32ARR)
representation statically -- the same rule `linalg.lisp` documents. The reason is not
memory: **a packed single-float array IS a GPU vertex buffer's bytes**, and `objc:data`
takes one of any rank, so `geom:mesh` -> `objc:data` -> `setVertexBytes:` is the whole
path with no conversion (`objc.md`, "Metal"). Every `linalg` transform preserves the
input width, so a `#f` value never widens back to `#d` on the way through.

## The winding convention

Each facet is an index loop wound **counter-clockwise seen from OUTSIDE**. `volume`
integrates the divergence theorem over the mesh triangles, so a facet wound the wrong
way SUBTRACTS: a mis-wound solid answers a grossly wrong number rather than a slightly
small one, and `volume` therefore doubles as the winding test for every constructor.
`GeomLibraryTest` and the ci-spec cases rest on that.

A tessellated primitive is INSCRIBED in its smooth ideal, so every measured volume
converges on the closed form **from below**. Measured 2026-08-29 (spike, re-checked on
the shipped library):

| solid | measured volume | closed form | error |
|---|---|---|---|
| `(box '(100 200 300))` | 6000000.0 | 6000000 | exact (area 220000 exact too) |
| `(cylinder :radius 50 :height 100 :sides 64)` | 784137 | 785398 | -0.16% |
| `(sphere :radius 50 :sides 32 :stacks 24)` | 518015 | 523599 | -1.07% |
| `(torus :radius 60 :tube 20 :sides 48 :rings 24)` | 467012 | 473741 | -1.42% |
| `(cone :radius 50 :height 120 :sides 64)` | 313655 | 314159 | -0.16% |

Those integers are the ci-spec expectations; a change that shifts one is either a
tessellation change or a bug.

## The cached mesh -- the load-bearing invariant

`geom:mesh` answers the solid's triangles in MODEL space: 18 floats a triangle (three
corners of position + normal), fan-triangulated per facet with a Newell normal (correct
for a slightly non-planar loop, and it never crosses a degenerate pair). Computed once
and kept in `geom::%mesh`; `geom:wireframe` (each edge once, 6 floats a segment) in
`geom::%wire`.

**This is a design decision, not an optimization.** A rigid solid's triangles never
change; only its pose does. Measured 2026-08-29 on an Apple M4 Max
(`.todo/563-solid-modeling-and-a-3d-viewer/bench.lisp`), a 30-joint chain of cylinders
and spheres -- 60 solids, 13,800 triangles:

| design | per frame |
|---|---|
| A: transform every vertex into world space every frame | **380 ms** (2.6 fps) |
| B: cached model-space mesh + one 4x4 matrix per solid | **9.0 ms** |

42x, and B pays 179 ms ONCE at load time for those 60 solids. So `geom:mesh` is part of
the PUBLIC surface rather than a renderer's internal detail, and a renderer must touch
no triangle during a frame. A later change that regresses this is visible against those
two numbers.

`geom:scale` is the only vertex mutation the package offers and therefore the only
place that has to invalidate: it drops `%mesh`, `%wire` AND `user-data`. Any future
vertex mutation must call `geom::%invalidate-mesh`.

`user-data` is a slot a consumer hangs its own state on -- a renderer keeps its GPU
buffers there. **It is a slot because a hash table cannot key on a node at all.** The
spike's reason was liveness (a hash of a node was exponential in the graph reachable
from it) and that half is gone -- the work budget bounds it, so such a `gethash`
RETURNS (`hash-tables.md`, 2026-08-29). What remains is correctness, and it is the
worse half: `:test 'eq` is accepted and IGNORED, so a table compares its keys
STRUCTURALLY (`.todo/012-hash-table-test-semantics.md`), and two sibling nodes with
equal slots -- routine in a scene graph -- collide into ONE entry. A wrong answer, not
a slow one. The slot would still be the right design after 012 lands (the cache lives
with the thing it caches, and `detach` cannot orphan it), so nothing here changes then;
only the comment's reason would shrink to that last sentence.

## Boolean operations (union / difference / intersection / section)

**The algorithm is BSP-tree clipping -- the csg.js formulation -- chosen over the
classic face-splitting/classification pipeline** (decided 2026-08-29, `.todo/566`).
Each operand's world-space boundary polygons go into a binary space partition, the
two trees clip each other, and the surviving fragments are the result's boundary
(`geom::%bsp-*` in geom.lisp, ~250 lines). Why this trade:

- the BSP formulation has NO per-degeneracy special cases -- one epsilon in
  `geom::%split-polygon` classifies every point, so coplanar faces, a vertex/edge on
  a face, exact touching and a through-hole with coplanar caps are all the same code
  path (the ci-spec case and `GeomLibraryTest` pin each one);
- its weakness -- it fragments faces that did not strictly need splitting -- costs
  `geom` nothing, because a facet is fan-triangulated for rendering anyway and
  `volume`/`surface-area` are per-triangle sums. The face-splitting pipeline earns
  its complexity only when face structure must survive (feature naming, exact face
  counts), which nothing here needs. Revisit only if that changes.

The load-bearing decisions:

- **The pipeline runs in float64 and narrows on the way out.** Scalar arithmetic is
  double and an `aref` of a packed float32 vertex WIDENS, so the pipeline points
  (plain `(x y z)` lists) carry 29 more bits than the data; the result narrows to
  float32 once, in `%build-solid`'s vertex array. No representation change was
  needed and the cost is unmeasurable next to the interpretation overhead.
- **`geom:*tolerance*` is RELATIVE** (default 1.0e-5): the classification epsilon is
  `(* geom:*tolerance* extent)` with extent the largest side of the operands'
  combined world bounds (`geom::%operand-epsilon`). geom has no unit of length, so
  an absolute epsilon cannot serve a 0.001-scale and a 1000-scale model at once;
  `GeomLibraryTest.theToleranceIsRelative...` pins both scales.
- **Operands in WORLD coordinates, untouched; the result is a new ROOT solid** with
  world-coordinate vertices and an identity local transform. `(geom:history result)`
  answers `(op a b)` (the `history` slot on `geom:solid`, nil for primitives), which
  is what lets a program re-run a model at a different parameter.
- **Result vertices are welded on the epsilon grid** (`geom::%weld-key` in
  `geom::%csg-solid`): a shared edge split from both sides lands on one key even
  when the two interpolations differ in the last bits, so the shell closes; the
  winding survives clipping (an inverted polygon is reversed), so `volume` stays the
  winding check on the RESULT too -- the volume oracle
  `vol(A u B) + vol(A n B) = vol(A) + vol(B)` in the tests is also the normals test.
- **An empty result is an EMPTY solid** (zero-row vertex array, no facets, volume
  0.0), not an error: disjoint operands intersected answer it.
- **`geom:section`** is the same classification with one operand trivial: per-facet
  plane segments (`geom::%facet-section`, oriented along plane-normal x facet-normal
  so outer loops wind counter-clockwise seen from +normal and holes clockwise),
  stitched on the weld grid into closed loops; an unclosed chain -- a tangent touch
  -- is dropped rather than answered broken. A facet lying IN the plane is skipped;
  its boundary comes from its neighbours' edges (a section exactly on a box face
  answers that face's loop).

## Pruning

`geom` is large and a program that uses `box` alone must not carry `revolution`'s
tessellator. Every geom definition is a `defun`/`defconstant`, so `LibraryDefunPruner`
keys it by name and the fixpoint reaches only what the program calls; the four
`defclass` forms are unkeyed and stay roots, which is the type model and nothing more.
Measured: `(print (geom:volume (geom:box 10)))` compiled to a `.class` carries 15 geom
methods (vec3, %unit, %identity-rotation, axis-vector, axis-angle-matrix, rpy-matrix,
make-transform, %build-solid, mesh, %facet-normal, box, volume and the generated
accessors) and none of cylinder / cone / sphere / torus / revolution / extrusion /
wireframe / surface-area / centroid / bounds.

## Cross-backend parity

The modeling half is trigonometry over float32, which is exactly where four backends
could disagree, so the pin is `ci-spec.yaml`'s `geom-solids-cross-backend` and
`geom-transforms-cross-backend`: the exact answers (a box's volume and area, its bounds
and centroid, a prism's 120.0, the poses that land on integers) are printed verbatim
and pin the packed single-float printer too, while every trigonometric answer is scaled
and ROUNDED so a float32 last bit cannot fail the case. Verified byte-identical on the
interpreter, a compiled JVM class, WASM preview 1 and the WASI 0.3 component
(2026-08-29).

## The renderer: `metal` and `scene` (todo-565, 2026-08-29)

Two more shipped Lisp-source libraries, `eval/metal.lisp` + `eval/MetalLibrary` and
`eval/scene.lisp` + `eval/SceneLibrary`, wired exactly as the table above wires geom
(splice class, `PackageRegistry` entry, `LispEvaluator` lazy load, `LibraryDefunPruner`,
`resource-config.json`, `doc/{en,ja}`) with three differences:

- **They are macOS-only.** Both bottom out in `objc:send`, so `CompileFrontend` refuses a
  `.wasm` output naming the reference -- `AppKitLibrary.firstObjcReference` answers for all
  four macOS packages (`objc`, `appkit`, `metal`, `scene`), which is why it lives there and
  not in one library per package. The browser playground refuses them the same way.
- **The splice chain runs them in dependency order, innermost first:** `SceneLibrary`
  before `MetalLibrary` before `GeomLibrary`/`LinalgLibrary` before `AppKitLibrary`, so
  each pass sees the references the previous one introduced (`scene` names `geom:`,
  `metal:`, `linalg:` and `appkit:`; `metal:run`'s clock is `appkit:timer`).
- **`metal` must stay usable without `geom` or `scene`** -- the four
  `examples/macos/metal-*.lisp` drive it directly. Its promotion, its frozen export list
  and what it cost are `.kb/objc.md`, "Metal".

The thread facts it inherits are `.kb/objc.md`'s and are not restated here: `appkit:timer`
is the clock, every hop is `objc:on-main` (inline when already on thread 0), and a callback
runs on thread 0 with the interpreter's GLOBAL dynamic bindings.

**No triangle is touched by Lisp during a frame.** That is the invariant the two numbers
above buy, and `scene.lisp` is built for it:

- each solid's `geom:mesh` and `geom:wireframe` go into `MTLBuffer`s of their own the
  FIRST time the solid is drawn, and the entry -- `(mesh-buffer tri-count wire-buffer
  segment-count axis-length)` -- lives in `geom:user-data`, not in a table keyed by the
  solid (the reason is the `user-data` paragraph above);
- the vertex function takes `vp` and `model` as SEPARATE uniforms and transforms the normal
  by `model` too, so a solid that moves needs no re-upload and a frame's whole CPU cost is
  one 4x4 matrix and one draw call per solid;
- lines -- the ground grid, the axis triads, every wireframe -- go through a second
  pipeline with `metal:+line+` (`MTLPrimitiveTypeLine` = 1), which the promotion added to
  the `metal` surface rather than leaving it defined locally.

Three things the spike (`.todo/563-solid-modeling-and-a-3d-viewer/scene.lisp`) did not do
and the shipped file does:

- **The callbacks are keyed by VIEW, not by an `*active*` global.** AppKit's callbacks are
  process-wide, so one `objc:define-class "RontoLispSceneView"` serves every viewer and
  `scene::*views*` maps `(objc:address view)` -> viewer -- `appkit::*actions*` keyed by
  widget address is the precedent (`.kb/objc.md`). Two viewers therefore orbit
  independently, which is the whole reason a viewer is an instance.
- **Resize follows the window.** The view posts `NSViewFrameDidChangeNotification` to one
  shared observer (`setPostsFrameChangedNotifications:` is not optional -- without it
  NSView posts nothing), which finds the viewer by the notification's object and calls
  `metal:resize` plus a redraw; the projection's aspect follows the stored width/height.
- **The camera gestures redraw themselves and the mutators do not.** A drag that changed
  the camera and drew nothing would make the documented "drag to orbit" false on a viewer
  that is not animating; a loop adding sixty solids that drew sixty frames would be the
  opposite mistake. So `scene::%on-mouse-dragged` / `%on-scroll` / `%on-frame-changed` call
  `scene:refresh` and `scene:add` / `camera` / `grid` / `shading` / `axes` do not.

`MetalLibraryTest` and `SceneLibraryTest` cover the library as a LIBRARY (the public names
match the registry exactly, the splice fires exactly when referenced, the pruner drops what
a program does not call, the WASM refusal names the package) and no test opens a window.
Verified by hand on all three carriers on 2026-08-29 -- `java -jar`, the native binary, and
`-o Two.class` under `java` plus `-o two.jar` under `java -jar` -- with a probe that
asserts two viewers route independently, that a frame reaches the encoder, that the
per-solid buffers are built once, and that a `setFrame:display:` on the window moves the
viewer's width. What the RENDERER does is the section below.

## How the renderer is tested (todo-568, 2026-08-29)

No test may open a window (`.kb/objc.md`), which left the camera, the projection, the
per-solid model matrix, the winding convention and the depth test -- arithmetic that breaks
silently and is obvious in a picture -- with nothing checking them. **`scene:offscreen`
closes that, and the load-bearing fact is that it is not a second render path.**
`metal:offscreen` builds a `metal:context` whose `target` slot holds a shared-storage
BGRA8 texture instead of a `CAMetalLayer`; `metal:frame` asks that slot once per frame and
takes the drawable's texture or the context's own, so ONE encoding path serves both, and an
offscreen frame is `waitUntilCompleted`'d instead of presented. `metal:pixels` reads it
back with `getBytes:bytesPerRow:fromRegion:mipmapLevel:` into an `objc:data` block --
`width*height*4` bytes, BGRA, row 0 at the top, deliberately NOT converted to RGBA, since
the format is the layer's. `scene:offscreen` and `scene:viewer` then differ only in the
context they hand `scene::%viewer-over`.

`SceneOffscreenRenderTest` (macOS-gated, skipped without a Metal device) asserts the five
things a picture makes obvious and a number does not: a red box is red in the middle and
background in the corners; a solid added FIRST is not overwritten by one added behind it
(the depth attachment); a single facet wound counter-clockwise seen from outside draws and
the same facet reversed is culled (the winding); `scene:fit` leaves no solid pixel on the
frame border from four camera angles; and the same scene renders byte-identical twice. Each
frame is also written to `target/scene-frames/*.png` and every assertion names its file --
the PNG writer is `javax.imageio` in the TEST, not a rung of `metal`, because a diagnostic
does not belong on the shipped surface.

Two changes the test forced, both improvements:

- **`scene::%render` now sets `setFrontFacingWinding:` + `setCullMode:` explicitly.** It
  culled nothing before. geom winds a facet counter-clockwise seen from outside and Metal
  decides facing in CLIP space (y up), not in the y-down framebuffer, so the front winding
  is `metal:+winding-counter-clockwise+` -- measured, not reasoned: the first cut said
  clockwise and drew every solid's FAR surface, which a centrally symmetric test object
  (a cube) cannot tell apart from the near one. The pinning shape is therefore a single
  quad, not a box.
- **`(scene:grid v :extent nil)` drops the grid**, the way `(scene:axes v nil)` drops the
  triads. A viewer that is a picture of one solid wanted it and there was no way to say it.

**A `geom:volume` oracle cannot detect an inverted solid**: the divergence integral is
`abs`'d, and a point reflection (`(geom:scale s -1)`, the cheap way to invert a mesh) leaves
each triangle's normal unchanged while moving it to the antipode. So the winding check is
the renderer's, not the modeller's, and the two agree by construction rather than by
measurement.

## The browser twin

`examples/browser/webgl-solids/` is the renderer `scene` cannot be: the same design over
WebGL2, so `geom` has a viewer wherever it runs (`.kb/wit.md`, a `--no-wasi` reactor). It
consumes `geom:mesh` and `geom:world-transform` UNCHANGED and contains no modeling code at
all -- a second modelling layer in the browser is exactly how `geom` would grow a browser
dialect and how the two renderers would drift. The differences are the two that were
expected: OpenGL's clip space puts z in [-1, 1] where Metal's puts it in [0, 1] (one row of
the projection), and WebGL renames a buffer behind your back where Metal makes a rewritten
buffer rotate copies -- which costs the twin nothing, since a mesh here is uploaded once
and never rewritten. Culling needs no statement at all on that side: GL's default front
winding is already counter-clockwise, which is geom's.

## `IndentRules`

**No entry is needed.** Not one member of this package takes a body -- every one is a
function whose trailing arguments are values or keywords -- so `rontolisp format` laying
a call out as a function call is correct. A future member that DOES take a body (a
`with-...` shape) would need one (`formatter.md`).
