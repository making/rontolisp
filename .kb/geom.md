# The `geom` package (solid modeling)

One hand-written Lisp library, `src/main/resources/am/ik/rontolisp/eval/geom.lisp`, following the
`linalg.lisp` / `appkit.lisp` pattern (`linalg.md`, `objc.md`) so one implementation runs identically
on every backend: rigid `geom:transform` values, a scene-graph `geom:node`, boundary-represented
`geom:solid`s with a cached model-space triangle mesh, nine primitive constructors plus `triad`, the
measurements (`bounds`, `volume`, `centroid`, `surface-area`), the booleans and five model-file
readers. 63 exported functions plus `geom:*tolerance*` and four CLOS class names.

**The MODELLING half reaches for nothing but `linalg`** -- no `objc:`, no `java:`, no `SourceLoader`
-- so the same solids tessellate in the browser playground and in a `.wasm`; `scene` (the macOS
viewer) is a CONSUMER. **The one exception is the five READERS** (`read-obj`/`read-stl`/`read-ply`/
`read-gltf`/`read-model`): ANSI CL I/O on all four backends, dropped by `LibraryDefunPruner` from any
program that calls none. Nothing else here may do I/O.

## Wiring (the `linalg` pair, exactly)

| piece | where |
|---|---|
| source | `eval/geom.lisp` |
| splice class | `eval/GeomLibrary` (`forms()`, `process(program)`, `isGeomQualified`, `mentionsGeomClass`, `CLASS_NAMES`) |
| package | `PackageRegistry.GEOM_FUNCTIONS` + `LispNames.GEOM_PKG` + `geomFunctionNames()` + `BUILTIN_PACKAGE_NAMES` |
| interpreter | `LispEvaluator.ensureGeomLoaded()` on the first `geom:`-qualified FUNCTION resolution, plus `ensureGeomClassesFor(form)` at the seven `ensureAsdfClassesFor` sites |
| compile path | `cli/CompileFrontend`: `GeomLibrary.process` INSIDE `LinalgLibrary.process` (beside `TorchLibrary`); `JsonLibrary.process` OUTSIDE both, since `geom:read-gltf` parses through `rontolisp:json-parse`. The browser playground repeats the nesting. |
| pruning | `LibraryDefunPruner.prunableNames()` collects `GeomLibrary.forms()` |
| interpreter natives | `eval/GeomKernels.install`, from `ensureGeomLoaded` |
| JVM kernels | `codegen/jvm/JvmGeomKernelCompiler` (call site) + `JvmGeomTemplate` (bridge) + `JvmGeomRuntimeBuilder` (injection), gated in `JvmLispCompiler` on `gateMembers()`, one `JvmExprCompiler.compileCons` case |
| docs | `doc/{en,ja}/guides/solid-modeling.md`, 63 pages under `reference/functions/geom-*.md` |

- **Trap -- the class-mention trigger is not optional.** The lazy load fires on FUNCTION resolution,
  but a `defmethod` specializer, `typep`, `typecase`, `make-instance` or a `defclass` superclass can
  name `geom:solid` without calling any geom function and would quietly answer `nil`. That is
  `GeomLibrary.mentionsGeomClass` / `LispEvaluator.ensureGeomClassesFor`; a new geom class must be
  added to `GeomLibrary.CLASS_NAMES`. The compile path needs no equivalent.
- **Trap -- a library-internal helper may not be named `%make-<class>`.** `expandDefclass` generates
  constructors as `PKG::%make-<member>`, so a hand-written `geom::%make-solid` SILENTLY replaced
  `geom:solid`'s constructor. The builder is `geom::%build-solid`.
- **Ordering seam:** the printing operators route BEFORE evaluating the argument, so the first
  `(print (geom:box ...))` of a session must load geom first -- `LispEvaluator.referencesGeom`, the
  twin of the torch pre-load beside it.

## The type model

- **`transform` is a VALUE, not a superclass**: translation 3-vector + 3x3 rotation, read-only slots.
  `compose`/`invert`/`transform-point`/`inverse-transform-point` build new ones, and so do the node
  mutators -- `translate`/`rotate`/`place`/`reorient` REPLACE `geom::%local` with a fresh transform,
  so one transform can be handed to several nodes safely.
- **`node` HAS a transform rather than being one.** `world-transform` is memoized in `geom::%world`
  and `geom::%stale` drops it down the WHOLE subtree on every pose change; every node slot is
  internal (`%local`/`%parent`/`%children`/`%world`) so no `setf` can leave the memo stale.
- **`solid` is a node carrying a boundary representation.** `vertices` is ONE rank-2 `(n 3)` packed
  array of MODEL coordinates, making a whole-solid transform a single `linalg:matmul`
  (`%solid-bounds` is the one place that does it); `facets` is a list of index loops.
- **`:frame` is a keyword, not a positional flag**; `:local` is the default.
- **float32 everywhere**: every array is `:element-type 'single-float`, the literal spelled at each
  `make-array` so all four backends pick `float[]` (TYPE_F32ARR) statically. A packed single-float
  array IS a GPU vertex buffer's bytes, so `geom:mesh` -> `objc:data` -> `setVertexBytes:` needs no
  conversion (`objc.md`, "Metal").

## The winding convention

Each facet is an index loop wound **counter-clockwise seen from OUTSIDE**. `volume` integrates the
divergence theorem over the mesh triangles, so a mis-wound facet SUBTRACTS: `volume` is the winding
test for every constructor. A tessellated primitive is INSCRIBED in its smooth ideal, so measured
volume converges from below. These integers are ci-spec expectations; a shift is a tessellation
change or a bug:

| solid | volume | closed form |
|---|---|---|
| `(box '(100 200 300))` | 6000000.0 | exact (area 220000 exact too) |
| `(cylinder :radius 50 :height 100 :sides 64)` | 784137 | 785398 |
| `(sphere :radius 50 :sides 32 :stacks 24)` | 518015 | 523599 |
| `(torus :radius 60 :tube 20 :sides 48 :rings 24)` | 467012 | 473741 |
| `(cone :radius 50 :height 120 :sides 64)` | 313655 | 314159 |

**Trap -- `volume` is the winding test, not the whole mesh's test.** `geom:revolution` used to cap
BOTH ends of a profile whose ends are the same point (a torus's closed cross-section); the two
coincident discs wind opposite ways, so the divergence integral cancelled them and volume stayed
right while a torus rendered as a filled disc. The rule is `geom::%closed-profile`: a closed profile
gets neither cap. The pins assert the AREA (47,155 rounded) and the FACET COUNT (1,152 =
`sides * rings`), which a cancelling pair cannot hide behind. Likewise **volume cannot detect an
INVERTED solid** (the integral is `abs`'d), so that winding check is the renderer's.

## The cached mesh -- the load-bearing invariant

`geom:mesh` answers the solid's triangles in MODEL space: 18 floats a triangle (three corners of
position + normal), fan-triangulated per facet with a Newell normal, computed once into
`geom::%mesh`; `geom:wireframe` (each edge once, 6 floats a segment) into `geom::%wire`. A rigid
solid's triangles never change, only its pose does: on a 30-joint chain (60 solids, 13,800
triangles), transforming every vertex into world space every frame is 380 ms/frame against **9.0 ms**
for the cached mesh plus one 4x4 matrix per solid. So `geom:mesh` is PUBLIC surface and **a renderer
must touch no triangle during a frame**.

`geom:nscale` is the only vertex mutation and therefore the only place that must invalidate: it drops
`%mesh`, `%wire` AND `user-data`. **Any future vertex mutation must call `geom::%invalidate-mesh`.**

`user-data` is a slot a consumer hangs its own state on. **It is a slot because a hash table cannot
key on a node**: `:test 'eq` is accepted and IGNORED, so a table compares keys STRUCTURALLY
(`hash-tables.md`) and two sibling nodes with equal slots collide into ONE entry.

## Scaling and the package's verb convention

`translate`/`rotate` ACCUMULATE onto a node's local transform; `place`/`reorient` SET it -- POSE
mutators, so plain verbs. **The package RULE: a GEOMETRY operation builds by default and offers an
`n`-spelling for in-place; a pose or graph mutator (`attach`, `detach`, `place`...) does neither.**

- **`geom:scale` is functional**: a new UNATTACHED root solid with scaled vertices, the facets,
  colour and label, and `(:scale s factor)` in its `history`; parent, children and `user-data` are
  not carried. **`geom:nscale` is in-place** and the package's ONLY vertex mutation. The factor may
  be a number or a 3-vector/list; the mesh is rebuilt with a fresh Newell normal.
- **A mirroring factor (negative determinant) FLIPS the facets**, reversing each loop so the winding
  stays outward. Volume cannot see this, so the pin is the mesh normal of a mirrored box's `+x` face.
  A ZERO component is refused, naming the function and the factor.
- **The transform stays RIGID -- no scale slot on `geom:transform` or `geom:node`, settled.**
  `volume`, `centroid` and `surface-area` are computed from the MODEL-space mesh and know nothing of
  the node's transform, so a scaled NODE would silently report unscaled measurements;
  `%world-polygons`, `%solid-bounds`, the per-draw model uniform in `metal.lisp`/`scene.lisp` and
  `invert` would each need the scale threaded through.

## The printed representation

`defmethod print-object` on exactly TWO of the four classes, both `print-unreadable-object :type t`
so `princ` drops the package qualifier (`.kb/clos.md`):

- **`geom:solid`** -- `#<GEOM:SOLID "b" 8 vertices 6 facets>`: the label when there is one
  (prin1-quoted so it cannot be misread as a count) plus the two counts.
- **`geom:node`** -- `#<GEOM:NODE 2 children>`: the child count and NOTHING that walks the graph.
  `parent` and `children` point at each other, so the default renderer recursed into a
  `StackOverflowError` after `(geom:attach a b)`; the cycle guard (`.kb/pretty-printer.md`) makes
  that finite now, but `#` markers are a fallback.
- **`geom:transform` and `geom:bounds` deliberately have NO method**: their slots ARE the value.
- A `defmethod print-object` in a spliced library turns on the printer route (`printObjectTags`,
  `.kb/clos.md`) for EVERY program that splices geom, on all three compile backends (+9-13%); a
  program that does NOT reference geom is byte-identical.

## Reading a model file

The four readers plus the dispatcher `geom:read-model` answer a `geom:solid` built through
`geom::%build-solid` -- except `read-gltf`, which answers the LIST of solids its scene poses
(`read-model` passes the shape through).

- **A reader is `(path color label) -> a geom:solid`, or a LIST of them.** A node HIERARCHY rides
  inside a list: the solids are attached to a shared `geom:node`, so each one's `world-transform`
  carries its parents and the flat list still draws right.
- **The one internal representation is `geom::%build-solid`'s arguments**: points, index loops, a
  colour, a label -- NOT rich enough for glTF per-primitive materials, per-vertex normals, texture
  coordinates or per-vertex colours. A reader reads those records past.
- **Dispatch is a `case`, deliberately not a table**: a Lisp-level registry would make every reader
  reachable from the dispatcher, so a program reading one format would carry them all. **Adding a
  format is four localized edits**: one reader defun, one `geom::%model-format` clause, one
  `geom:read-model` case arm, one `PackageRegistry.GEOM_FUNCTIONS` entry.
- **`geom::%model-format` sniffs the file's own bytes** (one 512-byte read), falling back to the
  extension only where no content test can answer: `glTF` -> `:glb`; `ply` + a line break -> `:ply`;
  first token `solid` -> `:stl`; first token `v`/`vn`/`vt`/`f`/`g`/`o`/`s`/`usemtl`/`mtllib` ->
  `:obj`; first token opening with `{` -> `:gltf`; otherwise the extension. **A binary STL has no
  magic number at all**, so one whose 80-byte header is not the word `solid` is named by its
  extension; `:format` overrides everything. What the extension NEVER decides is the ASCII/binary
  split. The file-level refusal is only "cannot tell what format"; each reader names what IT cannot
  carry.
- **The dialect test does not use `file-length`**, though it answers on all four backends now
  (`fd_filestat_get` is the twelfth preview1 import): deciding from the file's SHAPE is one code path
  on all four and survives files the length test does not (`trimesh`'s `unit_cube.STL` is BINARY with
  `solid unit_cube` in its header). `read-sequence`'s short-fill answer replaces `file-length`.
- **Text parsing has no faster spelling available.** Per float on the interpreter:
  `read-from-string` 0.68 us, a parenthesised line through the reader 1.85 us, a hand-rolled
  `char-code` scan 20 us. The reader is 30x faster and UNUSABLE (it answers the SYMBOL `|1.30E-2|`
  for exponent notation on the WASM backends, chokes on `#` and `|`, reads `739/1` as a ratio, and
  drags the runtime reader into compiled output), so the scanner is `char-code` like `json.lisp`'s.
  Two interpreter primitives are pathologically slow, neither on this path nor investigated:
  `position` on a string is 27.6 us a call against `subseq`'s 0.5 us; `parse-integer` is 5.2 us.
- **Deliberately not here**: WRITING any format; vertex welding (a different operation, on a mesh
  from any source); a byte-vector or stream entry point -- `read-sequence`'s packed fast path
  declines every in-memory stream, so the missing primitive is an in-memory byte stream that path
  accepts, not a second API.

### The Java kernels (interpreter and JVM backend)

Four file-scaled members are accelerated on both: `geom:read-obj` (the line walk and number scan,
packing into the `(n 3)` array), `geom:mesh` (fan triangulation + Newell normal), `geom:wireframe`
(the edge walk over an open-addressing `long` set instead of an `equal` hash table of conses) and
`geom::%vertex-extremes` (the posed min/max without materializing the array). On a 155 MB scanned
hand, read+mesh+wireframe+bounds+extent goes 539,577 ms -> 1,193 ms interpreted and 6,999 ms ->
1,277 ms compiled, printing identical bounds. NOT accelerated: `read-stl`, `read-ply`, `read-gltf`
(the ASCII PLY, 7.3 s for 3 MB, is the next one worth doing) and every modelling verb. WASM has no
equivalent (no `float[]` to pack into; Preview 1 reads through its own stream layer).

- `eval/GeomKernels` uses `LinalgSimd`'s interception seam verbatim (`.kb/linalg-simd.md`);
  `JvmGeomKernelCompiler` is the `geom:` sibling of `JvmLinalgKernelCompiler` (`.kb/linalg-blas.md`)
  -- a CALL-SITE compiler over temps, falling through an `IFNONNULL` to the spliced defun, into an
  embedded `JvmGeomTemplate` (`.kb/template-class-embedding.md`).
- **Both are ALWAYS ON, and `--simd` is not the precedent**: `--blas`/`--simd` are opt-in because a
  vendor gemm and a lane reduction REASSOCIATE, and nothing here does. **A kernel that rounds
  differently is a bug, not a tolerance.** The `setGeomKernels(false)` oracle switch (`LispEvaluator`,
  `JvmLispCompiler`) lets `GeomKernelsTest` / `JvmGeomKernelCompilerTest` run every fixture down both
  paths and compare PRINTED values.
- Three `geom.lisp` splits made the seam possible (a seam has to be a whole function to be
  replaceable): `geom::%solid-of-vertices`, `geom::%vertex-extremes` and `geom::%model-extent` -- the
  last the ONE `geom::` internal `scene.lisp` reaches for, the public bounds API being world-space only.
- **The gate is the CALL SITE, not the splice**: `JvmLispCompiler` scans the ALREADY-PRUNED program
  for `JvmGeomKernelCompiler.members()` (+16.8 KB where it arms). **`--dynamic` is excluded
  outright** -- it skips the pruner, and its point is that a call site honours a run-time redefinition.
- **All four members arm the bridge.** `geom::%vertex-extremes` used to be accelerated without arming
  anything, because `LibraryDefunPruner` counted a `defclass` header's own name as a function
  reference and `geom:bounds` is both (`.kb/library-defun-pruning.md`).
- **`geom:read-obj` is the one member whose accelerated answer is not the member's answer**: the
  bridge hands its packed array and index loops to the LISP `geom::%solid-of-vertices`. Its
  `:color`/`:label` tail is built into a rest list ONCE; a tail that is not those literal keywords
  declines at COMPILE time so the defun's own lambda list still signals about it.
- **The bridge can decline to exist**: `_geomInit` catches `LinkageError` (an older JRE answers
  `UnsupportedClassVersionError`), leaves `_geomAvailable` false silently, every call site tests
  `_geomReady()` first.

### PLY, glTF, and what real files taught

- `geom:read-ply` reads `ascii` and `binary_little_endian`; `binary_big_endian` is refused BY NAME,
  the packed `read-sequence` path being little-endian by contract (`binary-sequence-io.md`). The body
  is walked BY THE HEADER -- `x`/`y`/`z` from wherever the vertex element put them, everything else
  read past by its declared width, never guessed. Binary vertex blocks have three shapes, fastest
  first: all float32 -> ONE `read-sequence`, columns sliced; float32 x y z FIRST with fixed-width
  extras -> one three-float read plus a skip per row; anything else -> `geom::%ply-scalar`. Skipping
  is `geom::%skip-bytes` -- bounded reads through a scratch buffer, `file-position` answering nil by
  design on this build.
- `geom:read-gltf` handles `.glb` and `.gltf` with `.bin` files beside it or base64 `data:` uris; a
  remote uri is refused. One glTF node -> one `geom:node` posed by its TRS or matrix, one primitive
  -> one `geom:solid` under it, the answer the FLAT LIST under one shared root. **A node's scale is
  baked into vertices with `geom:nscale`** -- geometry, not pose -- accumulated down the tree; EXACT
  for uniform scales, while a non-uniform scale above a rotated child would shear and is refused by
  name, as is a node MATRIX whose columns are not orthogonal. Also refused by name rather than
  half-read: `mode` other than 4, sparse accessors, any `extensionsRequired` entry (Draco/meshopt),
  skins, animations, glTF 1.x. Verified on the Khronos corpus (Box.glb volume exactly 1.0; Duck
  identical in all three carriers at 1.1957991851442398).
- **The base64 path is the one place buffer bytes are assembled by Lisp arithmetic.** The shape to
  use when filling a large string: build into a fill-pointered character array (the one string shape
  `(setf (char))` writes in place everywhere, `adjustable-arrays.md`), then ONE `subseq` on the way
  out -- 82 ms compiled against 30.5 s on the 138 KB embedded Duck. Both quadratic halves that forced
  it are now CLOSED (`string-index-cost.md`, `string-write-runtime.md`).
- **A file carries its own units.** The Stanford bunny is 0.2 across (metres), a printable part 200
  (millimetres); `scene:fit`'s camera floor, the projection's frustum and the scroll clamp are all
  relative now, while `scene:grid`'s 600-unit default extent is NOT.
- **Winding is the file's own** -- a negative volume means clockwise-from-outside and the reader does
  not fix it. **Cross-format agreement is the parse's oracle**: the teapot from an OBJ and from an
  ASCII STL answer the same volume (25.770105759541867) and area to all 17 digits.
- Reader cost against a base `(print (geom:volume (geom:box 10)))` of `.class` 137,917 / `.wasm`
  144,437: `read-stl` +25,837/+33,851; `read-ply` +61,875/+73,468; `read-gltf` +108,179/+121,916
  (includes the JSON library); `read-model` +203,673/+247,011.

## Boolean operations (union / difference / intersection / section)

**The algorithm is BSP-tree clipping -- the csg.js formulation** (`geom::%bsp-*`, ~250 lines), with
NO per-degeneracy special cases: one epsilon in `geom::%split-polygon` classifies every point, so
coplanar faces, a vertex/edge on a face, exact touching and a through-hole with coplanar caps are one
code path. Its weakness (it fragments faces that did not need splitting) costs nothing here; revisit
only if face structure must survive.

- **The pipeline runs in float64 and narrows on the way out.** An `aref` of a packed float32 vertex
  WIDENS, so the pipeline points (plain `(x y z)` lists) carry 29 more bits than the data; the result
  narrows once in `%build-solid`'s vertex array.
- **`geom:*tolerance*` is RELATIVE** (default 1.0e-5): the classification epsilon is
  `(* geom:*tolerance* extent)`, extent the largest side of the operands' combined world bounds
  (`geom::%operand-epsilon`). geom has no unit of length, so an absolute epsilon cannot serve a
  0.001-scale and a 1000-scale model at once.
- **Operands in WORLD coordinates, untouched; the result is a new ROOT solid**, identity local
  transform, `(geom:history result)` answering `(op a b)`.
- **Result vertices are welded on the epsilon grid** (`geom::%weld-key` in `geom::%csg-solid`): a
  shared edge split from both sides lands on one key even when the two interpolations differ in the
  last bits, so the shell closes. Winding survives clipping, so `volume` stays the winding check on
  the RESULT; the oracle `vol(A u B) + vol(A n B) = vol(A) + vol(B)` is also the normals test.
- **An empty result is an EMPTY solid** (zero-row vertex array, no facets, volume 0.0), not an error.
- **`geom:section`** is the same classification with one operand trivial: per-facet plane segments
  (`geom::%facet-section`, oriented along plane-normal x facet-normal so outer loops wind
  counter-clockwise seen from +normal and holes clockwise), stitched on the weld grid into closed
  loops; an unclosed chain is dropped rather than answered broken, and a facet IN the plane is skipped.

## Pruning and cross-backend parity

Every geom definition is a `defun`/`defconstant`, so `LibraryDefunPruner` keys it by name and the
fixpoint reaches only what the program calls; the four `defclass` forms and the two
`defmethod print-object` forms are unkeyed roots. `(print (geom:volume (geom:box 10)))` carries 16
geom methods and none of cylinder/cone/sphere/torus/revolution/extrusion/wireframe/surface-area/
centroid.

**Trap (fixed): a `defclass` and a `defun` of the same name used to keep each other alive.**
`geom:bounds` is the package's one such name, so every geom program, `(print (geom:vec3 1 2 3))`
included, also carried `bounds`, `%solid-bounds`, `%vertex-extremes`, `bounds-union`, `compose` and
`world-transform`. The pruner now walks a class header by POSITION
(`.kb/library-defun-pruning.md`). `geom-solids-cross-backend` and `geom-transforms-cross-backend`
print exact answers verbatim -- pinning the packed single-float printer too -- while every
trigonometric answer is scaled and ROUNDED so a float32 last bit cannot fail the case.

## The arrow and the origin indicator

`geom:arrow` is a shaft and a pointed head as ONE solid; `geom:triad` is three of them -- +x red, +y
green, +z blue, labelled `"x"`/`"y"`/`"z"` -- as a LIST of solids that `scene:add` splices, `:at`
placing all three. **Built directly, not `union`-ed**: the seam is known at construction time. The
shell is a base cap, `n` shaft quads, `n` quads of the head's underside annulus and `n` head
triangles -- `3n + 1` facets, 142 triangles at 24 sides.

**Its volume is exact, which is what pins the winding**: a prism plus a pyramid on the same regular
n-gon, `(n/2) sin(2pi/n) * (r^2 * (len - head) + hr^2 * head / 3)`, so
`(geom:arrow :length 200 :sides 24)` is `32201.23...` exactly.

**A constructed arrow does NOT scale with the view distance, and the viewer's line triads still do.**
A solid has a size in world units; a `geom` that asked the camera anything would no longer be
backend-independent. The auto-scaled behavior stays on `scene:axes` (initform `nil`;
`scene::%build-axes` and the unit line buffer survive, and `:bodies`/`:both` size a per-body triad
from that body's own model extent). `geom:triad`'s default length is **200**; `geom:arrow`'s own
default is `1.0`, every other measurement a fraction of the length.

## The renderer: `metal` and `scene`

`eval/metal.lisp` + `eval/MetalLibrary` and `eval/scene.lisp` + `eval/SceneLibrary`, wired as geom
is, with three differences: they are **macOS-only** (both bottom out in `objc:send`, so
`CompileFrontend` refuses a `.wasm` output naming the reference --
`AppKitLibrary.firstObjcReference` answers for all four macOS packages, which is why it lives
there); **the splice chain runs innermost first** (`SceneLibrary` before `MetalLibrary` before
`GeomLibrary`/`LinalgLibrary` before `AppKitLibrary`); and **`metal` must stay usable without `geom`
or `scene`** -- the four `examples/macos/metal-*.lisp` drive it directly (`.kb/objc.md`, "Metal",
which also holds the thread facts).

**No triangle is touched by Lisp during a frame**: each solid's `geom:mesh`/`geom:wireframe` go into
`MTLBuffer`s of their own the FIRST time it is drawn and the entry `(mesh-buffer tri-count
wire-buffer segment-count axis-length)` lives in `geom:user-data`, not a table keyed by the solid;
the vertex function takes `vp` and `model` as SEPARATE uniforms and transforms the normal by `model`
too, so a moving solid needs no re-upload; lines go through a second pipeline with `metal:+line+`
(`MTLPrimitiveTypeLine` = 1).

- **The callbacks are keyed by VIEW, not by an `*active*` global.** AppKit's callbacks are
  process-wide, so one `objc:define-class "RontoLispSceneView"` serves every viewer and
  `scene::*views*` maps `(objc:address view)` -> viewer (`appkit::*actions*` is the precedent).
- **Resize follows the window**: the view posts `NSViewFrameDidChangeNotification` to one shared
  observer -- **`setPostsFrameChangedNotifications:` is not optional**, without it NSView posts
  nothing.
- **The camera gestures redraw themselves and the mutators do not.** `scene::%on-mouse-dragged` /
  `%on-scroll` / `%on-frame-changed` call `scene:refresh`; `add`/`camera`/`grid`/`shading`/`axes`
  do not.
- **`scene:add` takes a solid or a LIST of solids, spliced in order**, refusing anything else THERE
  via `scene::%check-solid` BEFORE the first is consed in, so a refused call leaves the viewer as it
  was. `scene:drop` takes the matching shape; `nil` adds nothing.
- **`scene:ray v x y` is the primitive and answers a LINE** (`(origin direction)`, world space, from
  view coordinates in AppKit points -- origin bottom-left, `+y` up); **`scene:on-click v hook` is the
  convenience and answers a POINT**, where that ray meets the plane through the ORBIT TARGET facing
  the camera. `nil` removes the hook; it runs on the main thread. **A click is a press released
  without travelling more than 4 points** (`scene::%moved`); the deadzone is in the RELEASE arm and
  deliberately not in the drag arm, since a drag ignoring its first four points would jump. A
  shift-drag (the pan) never produces a click.

**Testing without a window** (`.kb/objc.md` forbids one): **`scene:offscreen` is not a second render
path** -- `metal:offscreen` builds a `metal:context` whose `target` slot holds a shared-storage BGRA8
texture instead of a `CAMetalLayer`, and `metal:frame` asks that slot once per frame, so ONE encoding
path serves both (an offscreen frame is `waitUntilCompleted`'d instead of presented).
`metal:pixels` reads it back with `getBytes:bytesPerRow:fromRegion:mipmapLevel:` into an `objc:data`
block -- `width*height*4` bytes, BGRA, row 0 at the top, deliberately NOT converted to RGBA.
`SceneOffscreenRenderTest` (macOS-gated) asserts colour, depth ordering, culling, `scene:fit` framing
from four angles, byte-identical repeat frames and the arrow/triad pixel shapes, writing each frame
to `target/scene-frames/*.png` (the PNG writer is `javax.imageio` in the TEST, not a rung of
`metal`). **`scene::%render` sets `setFrontFacingWinding:` + `setCullMode:` explicitly** -- it culled
nothing before, and since Metal decides facing in CLIP space (y up) rather than the y-down
framebuffer, the front winding is `metal:+winding-counter-clockwise+`, measured not reasoned: the
first cut said clockwise and drew every solid's FAR surface. **The pinning shape is a single quad,
not a box.**

## The browser twin

`examples/browser/webgl-solids/` is the renderer `scene` cannot be: the same design over WebGL2
(`.kb/wit.md`, a `--no-wasi` reactor). It consumes `geom:mesh` and `geom:world-transform` UNCHANGED
and contains no modeling code at all -- a second modelling layer in the browser is how the two
renderers would drift. Two differences: OpenGL's clip space puts z in [-1, 1] where Metal's puts it
in [0, 1] (one row of the projection), and WebGL renames a buffer behind your back. Culling needs no
statement: GL's default front winding is already counter-clockwise, geom's.

**The two renderers must orbit the same way, and one sign separates them.** Both drive the identical
two constants -- `(- azimuth (* 3.4 dx))` and a 2.6-scaled elevation term, clamped to +-1.5 -- over a
drag normalized by the viewer's height. **The trap:** a DOM client delta puts +y DOWN and AppKit's
`locationInWindow` puts +y UP, so the same code is two opposite cameras. The elevation term is
therefore negated in `scene::%orbit` and NOT in `scene::%view-point` -- the pan arm reads the same
delta and wants AppKit's sense, since a target moved AGAINST the drag is what makes the model follow
the cursor. (`dx` needs no flip; the browser twin has no pan.) Splitting the arithmetic out of
`%on-mouse-dragged` into `scene::%orbit`/`scene::%pan` is what makes this testable; untested is the
ONE line that reads the event, `scene::%view-point`, so a flip there would invert both gestures with
every test still green.

**An IK constraint on any consumer that composes rotations.** A chain posed by solving for a
WORLD-frame angular velocity has to carry it back into each joint's frame as `Rp^T Rot(w) Rp . R`,
and that sandwich DOUBLES the parent's orthogonality error into the child every time it runs: eight
solver iterations a frame down a five-joint chain is 2^8 a frame, and the error went 1e-16 -> 1e-2 in
five frames. Stating the Jacobian in each joint's OWN frame -- block `M . R` with `R` the joint's
world rotation, update `R . Rot(w)` -- removes the sandwich (drift ~1e-8 a frame) and is 1.5x faster.

## `IndentRules`

**No entry is needed.** Not one member takes a body, so `rontolisp format` laying a call out as a
function call is correct. A future member that DOES take a body would need one (`formatter.md`).

## Tests

- `eval/GeomLibraryTest`: `aClosedProfileIsCappedAtNeitherEndSoATorusHasAHole`,
  `anArrowIsAPrismPlusAPyramidAndIsExact`, `theToleranceIsRelative...`,
  `scaleAnswersANewSolidAndLeavesTheOriginalUntouched`,
  `nscaleMutatesInPlaceAndInvalidatesBothCachesAndTheUserDataSlot`,
  `aScaleFactorMayBeAVectorOrAListForANonUniformScale`,
  `aMirroringFactorFlipsTheFacetsSoTheWindingStaysOutward`, `aZeroScaleFactorIsRefusedNamingIt`,
  `aSolidPrintsItsLabelAndItsTwoCounts`,
  `aNodeInASceneGraphPrintsItsChildCountInsteadOfOverflowingTheStack`, `anAttachedSolidPrintsToo`,
  `aTransformAndABoundsStillPrintTheirSlots`,
  `theReadersArePrunedFromAProgramThatReadsNoModelFile`,
  `aProgramReadingOnePlyCarriesNeitherGltfNorJson`.
- `eval/GeomKernelsTest`; `codegen/jvm/JvmGeomKernelCompilerTest`;
  `eval/LibraryDefunPrunerTest#aDefclassDoesNotKeepTheDefunOfTheSameName`; `MetalLibraryTest`;
  `SceneLibraryTest` (which drives `scene:ray` and `scene::%click-point` over a bare `make-instance`,
  no window); `SceneOffscreenRenderTest` --
  `fitFramesASolidWhoseUnitsAreMetresRatherShrinkingItToADot`, `aTriadIsAddedAsOneArgument`,
  `aMeshReadOutOfAModelFileDrawsLikeAnyOtherSolid`.
- ci-spec: `geom-solids-cross-backend`, `geom-arrow-cross-backend`, `geom-transforms-cross-backend`,
  `geom-csg-cross-backend`, `geom-scale-cross-backend`, `geom-print-object-cross-backend`,
  `geom-read-model-cross-backend`, `geom-read-ply-gltf-cross-backend` (both PLY dialects, a GLB
  written from Lisp and read back -- 8000.0 the scale baked into vertices, `#f(10.0 0.0 0.0)` the
  pose that stayed rigid -- plus the big-endian refusal, verbatim).
