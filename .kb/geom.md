# The `geom` package (solid modeling)

One hand-written Lisp-source library,
`src/main/resources/am/ik/rontolisp/eval/geom.lisp`, following the `linalg.lisp` /
`appkit.lisp` pattern (`linalg.md`, `objc.md`) so a single implementation runs
identically on every backend: rigid `geom:transform` values, a scene-graph
`geom:node`, boundary-represented `geom:solid`s with a cached model-space triangle
mesh, nine primitive constructors plus the `triad` convenience, the measurements
(`bounds`, `volume`, `centroid`, `surface-area`), and the booleans (`union`,
`difference`, `intersection`, `section` -- see "Boolean operations" below). 57
exported functions plus `geom:*tolerance*` and four CLOS class names.

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
| tests | `eval/GeomLibraryTest` (interpreter), `ci-spec.yaml` cases `geom-solids-cross-backend`, `geom-arrow-cross-backend`, `geom-transforms-cross-backend` and `geom-csg-cross-backend` (all four backends) |
| docs | `doc/{en,ja}/guides/solid-modeling.md`, 57 pages under `reference/functions/geom-*.md` |

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

## The arrow, and where the origin indicator lives (todo-582, 2026-08-29)

`geom:arrow` is a shaft and a pointed head as ONE solid, and `geom:triad` is three of
them -- +x red, +y green, +z blue, labelled `"x"` / `"y"` / `"z"` -- as a LIST of
solids. They replace the origin indicator the viewer used to draw by itself: three
`metal:+line+` segments out of a private buffer (`scene::%build-axes`) under a model
matrix scaled by `0.16 * distance`, drawn whether or not the program asked for one.
The report against that was three complaints and they were one complaint -- **it was
not an object**: a line primitive has no width, so it could be neither thickened nor
tipped, and it was furniture rather than something a caller placed.

**Built directly, not `union`-ed.** A cylinder plus a cone through `geom:union` would
be correct and is one line, but it is a BSP clip ("Boolean operations" above) for a
composition whose seam is known at construction time, and the arrow is the one solid a
program may build several of per frame's worth of furniture. The shell is a base cap,
`n` shaft quads, `n` quads of the head's underside annulus and `n` head triangles:
`3n + 1` facets, `142` triangles at the default 24 sides.

**Its volume is exact, which is what pins the winding.** Every other tessellated
primitive converges on its closed form from below, so its test is a tolerance. The
arrow's closed form is the shape actually built -- a prism plus a pyramid on the same
regular n-gon, `(n/2) sin(2pi/n) * (r^2 * (len - head) + hr^2 * head / 3)` -- so
`(geom:arrow :length 200 :sides 24)` is `32201.23...` exactly, and a facet wound the
wrong way in any of the four families misses it by a mile rather than by a percent
(`GeomLibraryTest.anArrowIsAPrismPlusAPyramidAndIsExact`, `geom-arrow-cross-backend`).

The four decisions the report left open, and what they are:

- **A constructed arrow does NOT scale with the view distance, and the viewer's line
  triads still do.** A solid has a size in world units; that is what "an object placed
  at a point" means, and a `geom` that asked the camera anything would no longer be the
  backend-independent modeller the whole package rests on. The auto-scaled behavior is
  genuinely what a viewer's own furniture wants, so it stays exactly where it was, on
  `scene:axes`. `geom:triad`'s default length is **200**, which is what
  `0.16 * distance` draws at the viewer's default distance of 1200 (192, rounded to a
  number a caller can type); `geom:arrow`'s own default length is `1.0`, like every
  other constructor in the package, and every other measurement it takes is a fraction
  of the length so one keyword resizes the whole arrow.
- **`scene:axes`' `:bodies` and `:both` are unchanged.** The report is about the origin.
  A per-body triad is a different thing: it marks a frame at every solid, it is sized
  from that body's own model extent (`scene::%gpu-buffers`), and it is drawn under the
  body's world transform -- furniture that follows the model, not an object in it.
  Making those solids would mean building and uploading three meshes per body, per
  frame's worth of poses, to say something a hairline is better at.
- **The triad is a `geom` function returning solids, not a viewer mode.** Three arrows
  hung where the caller wants them is the honest spelling; `geom:triad` only saves the
  three calls and fixes the tints, and `:at` places all three. It returns a list, which
  `scene:add` splices -- see "What `scene:add` accepts" below.
- **`scene:axes`' initform is now `nil`.** Nothing is drawn that was not asked for. The
  modes are all still there, so this is a default change and not a removal;
  `scene::%build-axes` and the unit line buffer therefore survive, since `:world`,
  `:bodies` and `:both` all still draw out of it. Both shipped examples
  (`examples/macos/scene-*.lisp`) and every existing offscreen test already said
  `(scene:axes v ...)` explicitly, so nothing else moved.

The pixel evidence is `SceneOffscreenRenderTest`: an arrow's shaft is a measurable
number of pixels across and its head narrows to nothing at the tip, the head sits at
the end `:direction` names (`:-z` moves it to the other end of the frame), a bigger
`:radius` draws a wider shaft, a triad at the origin draws red, green and blue, and an
empty viewer is empty until `(scene:axes v :world)` is asked for. `target/scene-frames/
arrow-*.png` and `triad-at-the-origin.png` are the pictures.

## What `scene:add` accepts (todo-583, 2026-08-30)

**Every argument is a solid or a LIST of solids, spliced in order**, and anything
else is refused THERE, naming it. The report was `(scene:add *v* (geom:triad))`:
`triad` answers three solids by design, the list itself was consed into
`scene::%contents`, and the complaint arrived one frame later from inside the draw
callback as `No applicable method: GEOM:USER-DATA on CONS` -- a message naming
nothing the caller wrote, on a thread the caller is not on.

The two halves are one decision. Splicing is what makes the package compose: the
nine constructors that answer one solid and the one that answers three then go into
a viewer the same way, and no doc page has to spell a `dolist` around the odd one
out. The check is what makes a mistake a message: `scene::%check-solid` runs over
every argument BEFORE the first one is consed in, so a refused call leaves the
viewer exactly as it was.

`scene:drop` took the matching shape for the reason a container's two verbs should
agree -- what went in as one argument comes back out as one argument, so a viewer
given `(geom:triad)` is emptied of it by `(scene:drop v *triad*)` rather than by
three calls. `scene:clear` names no solid at all and needed nothing. `nil` is the
empty list and adds nothing; it is not an error, because splicing an empty list is
what `dolist` and `append` do with one.

Pinned by `SceneLibraryTest` (the splice, the order, the refusal's message, the
untouched viewer, `drop`'s shape) -- which needs no display, since a viewer's
CONTENTS need no window: `scene:viewer-state`'s contents slot has an initform, so
`(make-instance 'scene:viewer-state)` is a viewer as far as `add` and `drop` are
concerned. The picture is `SceneOffscreenRenderTest.aTriadIsAddedAsOneArgument`.

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

**The two renderers must orbit the same way, and one sign is all that separates them**
(2026-08-29). Both drive the identical two constants -- `(- azimuth (* 3.4 dx))`
and a 2.6-scaled elevation term, clamped to +-1.5 -- over a drag normalized by the viewer's
height, so a gesture that orbits one must orbit the other. The trap is that the two
dialects disagree about which way is up: a DOM client delta puts +y DOWN, and AppKit's
`locationInWindow` puts +y UP, so the same code is two opposite cameras. The elevation term
is therefore negated in `scene::%orbit`, and NOT in `scene::%view-point` -- the pan arm
reads the same delta and wants AppKit's sense as it stands, since a target moved AGAINST
the drag is what makes the model follow the cursor; flipping the point would invert the pan
along with the orbit. (`dx` needs no flip: right is +x in both.) The browser twin has no
pan at all, so the pan's convention is the native side's own.

Splitting the arithmetic out of `%on-mouse-dragged` into `scene::%orbit` / `scene::%pan` is
what makes any of this testable: an NSEvent cannot be built in a test, but a delta can, and
`SceneOffscreenRenderTest` drives both functions directly -- the elevation and azimuth a
30-pixel drag lands on, the clamp at either pole, the target a pan moves against the drag,
and the pixel composite the numbers cannot see (a marker on an opaque plate, in view when
dragging down puts the camera above it and hidden when dragging up puts the camera below).
What stays untested is the ONE line that reads the event, `scene::%view-point`: that
AppKit's `locationInWindow` is y-up is a premise, not an assertion, so a flip introduced
there would invert both gestures with every test still green.

## `IndentRules`

**No entry is needed.** Not one member of this package takes a body -- every one is a
function whose trailing arguments are values or keywords -- so `rontolisp format` laying
a call out as a function call is correct. A future member that DOES take a body (a
`with-...` shape) would need one (`formatter.md`).
