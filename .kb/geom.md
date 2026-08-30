# The `geom` package (solid modeling)

One hand-written Lisp-source library,
`src/main/resources/am/ik/rontolisp/eval/geom.lisp`, following the `linalg.lisp` /
`appkit.lisp` pattern (`linalg.md`, `objc.md`) so a single implementation runs
identically on every backend: rigid `geom:transform` values, a scene-graph
`geom:node`, boundary-represented `geom:solid`s with a cached model-space triangle
mesh, nine primitive constructors plus the `triad` convenience, the measurements
(`bounds`, `volume`, `centroid`, `surface-area`), the booleans (`union`,
`difference`, `intersection`, `section` -- see "Boolean operations" below) and the
five model-file readers ("Reading a model file" below). 63
exported functions plus `geom:*tolerance*` and four CLOS class names
(todo-586: `move`/`turn` became `translate`/`rotate`, and `scale` split into
the functional `scale` and the destructive `nscale` -- "Scaling" below).

**The MODELLING half reaches for nothing but `linalg`** -- no `objc:`, no `java:`, no
`SourceLoader`. That is the whole reason it ships rather than living in
`examples/macos/`: the same solids tessellate in the browser playground and in a
`.wasm`, and `scene` (the macOS viewer, "The renderer" below) is a CONSUMER of this
package, not a peer of it. Nothing may be added here that breaks that.

**The one exception is the five READERS** (`read-obj` / `read-stl` / `read-ply` /
`read-gltf` / `read-model`,
"Reading a model file" below), which open a file, and it is an exception the promise
survives rather than a hole in it: they are ANSI CL I/O on all four backends, and
`LibraryDefunPruner` drops them from every program that does not call one, so the
browser demo's module is measurably free of them. Nothing else here may do I/O.

## Wiring (the `linalg` pair, exactly)

| piece | where |
|---|---|
| source | `src/main/resources/am/ik/rontolisp/eval/geom.lisp` |
| splice class | `eval/GeomLibrary` (`forms()` parsed once and cached, `process(program)`, `isGeomQualified`, `mentionsGeomClass`) |
| package | `PackageRegistry.GEOM_FUNCTIONS` + `LispNames.GEOM_PKG` + `geomFunctionNames()` + `BUILTIN_PACKAGE_NAMES` |
| interpreter | `LispEvaluator.ensureGeomLoaded()` on the first `geom:`-qualified FUNCTION resolution, plus `ensureGeomClassesFor(form)` at the seven `ensureAsdfClassesFor` sites |
| compile path | `cli/CompileFrontend`, `GeomLibrary.process` INSIDE `LinalgLibrary.process` (beside `TorchLibrary`) so the `linalg:` references in the spliced geom bodies pull linalg in too, and `JsonLibrary.process` OUTSIDE both since 2026-08-31 -- `geom:read-gltf` parses through `rontolisp:json-parse`, so the geom splice introduces the reference Json must then rewrite; the browser playground repeats the nesting (a non-glTF geom program is byte-identical either way -- the json defuns prune back out, measured on `(print (geom:volume (geom:box 10)))`'s `.wasm`) |
| pruning | `LibraryDefunPruner.prunableNames()` collects `GeomLibrary.forms()` |
| tests | `eval/GeomLibraryTest` (interpreter), `ci-spec.yaml` cases `geom-solids-cross-backend`, `geom-arrow-cross-backend`, `geom-transforms-cross-backend`, `geom-csg-cross-backend`, `geom-scale-cross-backend`, `geom-read-model-cross-backend` and `geom-read-ply-gltf-cross-backend` (all four backends) |
| docs | `doc/{en,ja}/guides/solid-modeling.md`, 63 pages under `reference/functions/geom-*.md` |

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
  the node mutators -- `translate` / `rotate` / `place` / `reorient` REPLACE `geom::%local`
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
- **`:frame` is a keyword, not a positional flag.** `(geom:translate n v :frame :parent)`
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

**And `volume` is the winding test, not the whole mesh's test** (2026-08-30).
`geom:revolution` used to cap BOTH ends of a profile whose two ends are the same
point -- a torus's closed cross-section -- laying two coincident discs across the
hole. They wind opposite ways, so the divergence integral cancelled them exactly
and the torus's volume above was and is right; `surface-area` counted both
(87,252 against the closed form's 47,374, +84%) and the renderer drew whichever
one survived back-face culling, so **a torus rendered as a filled disc**. The
rule is now `geom::%closed-profile`: a closed profile has no end and gets neither
cap. The lesson is in the pin: both
`GeomLibraryTest.aClosedProfileIsCappedAtNeitherEndSoATorusHasAHole` and the
`geom-solids-cross-backend` ci-spec case assert the AREA (47,155 rounded) and the
FACET COUNT (1,152 = `sides * rings` and nothing else), because those are the two
numbers a cancelling pair of facets cannot hide behind. `examples/browser/
webgl-solids/solids.wasm` draws a torus and was rebuilt with the fix.

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

`geom:nscale` is the only vertex mutation the package offers and therefore the only
place that has to invalidate: it drops `%mesh`, `%wire` AND `user-data` (`geom:scale`
is FUNCTIONAL and builds a new solid -- "Scaling" below). Any future vertex mutation
must call `geom::%invalidate-mesh`.

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

## Scaling: the pose/geometry split, and the package's verb convention (todo-586, 2026-08-30)

`translate` and `rotate` ACCUMULATE onto a node's local transform; `place` and
`reorient` SET it. They are POSE mutators: they change where a node is, never what a
solid is, and CL does not `n`-prefix such operations, so they stay plain verbs. Scaling
is different in kind -- it rewrites a `solid`'s MODEL vertices -- so it follows CL's own
functional/destructive convention (`reverse`/`nreverse`, `union`/`nunion`), and that
convention is the package's RULE for future additions: a GEOMETRY operation builds by
default and offers an `n`-spelling for in-place, a pose or graph mutator (`attach`,
`detach`, `place`...) does neither.

- **`geom:scale` is functional**, like the booleans beside it: a new, UNATTACHED root
  solid carrying the scaled vertices, the facets, color and label, with
  `(:scale s factor)` in its `history`. Parent, children and `user-data` are not
  carried -- a solid already in a viewer wants `nscale`.
- **`geom:nscale` is the in-place version** and the package's ONLY vertex mutation:
  caches and `user-data` dropped, the same solid answered.
- **The factor may be a number or a 3-vector/list.** The mesh is rebuilt from the
  facets with a fresh Newell normal per triangle, so a non-uniform scale of a BREP
  costs nothing extra once the cache is dropped.
- **A mirroring factor (negative determinant -- an odd number of negative components)
  FLIPS the facets**, reversing each loop so the winding stays counter-clockwise seen
  from outside: mirroring is a real CAD operation (a left-hand part from a right-hand
  one) and this is its only spelling in the package, so refusing it would be an
  arbitrary limit, and answering an inside-out solid would be a bug. The pin is the
  mesh normal of a mirrored box's `+x` face, which volume cannot see (the integral is
  `abs`'d). A ZERO component would flatten the shell into a degenerate one and is
  refused naming the function and the factor.
- **The transform stays RIGID -- no scale slot on `geom:transform` or `geom:node`,
  settled.** A uniform scale would close mathematically (similarities form a group),
  but `volume`, `centroid` and `surface-area` are computed from the MODEL-space mesh
  and know nothing of the node's transform, so a scaled NODE would silently report its
  unscaled measurements; the CSG path (`%world-polygons`), `%solid-bounds`, the
  per-draw model uniform in `metal.lisp`/`scene.lisp` and `invert` would each need the
  scale threaded through. This is a CAD-flavoured package -- a boundary representation
  with booleans and real measurements -- and in CAD scaling changes the PART; the scene
  graph is for placement.

Pinned by `GeomLibraryTest` (`scaleAnswersANewSolidAndLeavesTheOriginalUntouched`,
`nscaleMutatesInPlaceAndInvalidatesBothCachesAndTheUserDataSlot`,
`aScaleFactorMayBeAVectorOrAListForANonUniformScale`,
`aMirroringFactorFlipsTheFacetsSoTheWindingStaysOutward`,
`aZeroScaleFactorIsRefusedNamingIt`) and the `geom-scale-cross-backend` ci-spec case
(all four backends).

## The printed representation (todo-584, 2026-08-30)

`geom.lisp` carries `defmethod print-object` on exactly TWO of its four classes:

- **`geom:solid`** -- `#<GEOM:SOLID "b" 8 vertices 6 facets>`: the label when there is
  one (prin1-quoted, so it cannot be misread as a count), then the two counts that say
  what the solid is. The default rendering was 520 characters for a box and 2,180 for a
  cylinder (measured 2026-08-30, the todo's numbers confirmed): every slot, the whole
  `:VERTICES` array, the full facet list, and -- once built -- 18 floats per triangle of
  `:MESH-CACHE` plus a renderer's GPU handles in `:USER-DATA`.
- **`geom:node`** -- `#<GEOM:NODE 1 child>` / `#<GEOM:NODE 2 children>`: the child
  count, and NOTHING that walks the graph. This is the correctness half, not the
  cosmetic one: `parent` and `children` point at each other, so after `(geom:attach a
  b)` the default renderer recursed the cycle into a `StackOverflowError` -- an attached
  node (every solid in a scene) could not be printed at all. The default renderer's own
  cycle guard (`.kb/pretty-printer.md`, "A cyclic instance graph") now makes that
  finite rather than fatal, but `#` markers are a fallback, not a representation; the
  method is what a caller should see.
- **`geom:transform` and `geom:bounds` deliberately have NO method.** Their slots ARE
  the value -- a rigid motion is its 12 numbers, a bounds its two corner points -- they
  hold no cache, and they cannot cycle (read-only slots, no node references), so the
  full default rendering is the honest print and a method would only hide it.

Both methods use `print-unreadable-object :type t`, so `princ` drops the package
qualifier exactly as every other typed `#<...>` does (`.kb/clos.md`).

**The cost, measured 2026-08-30** (`(print (geom:volume (geom:box 10)))`, before ->
after): `.class` 121,902 -> 137,123 (+15,221 B, +12.5%); `.wasm` (Preview 1) 130,351 ->
141,841 (+11,490 B, +8.8%). A `defmethod print-object` in the spliced library turns on
the printer route (`printObjectTags`, `.kb/clos.md`) for EVERY program that splices
geom, on all three compile backends -- that delta is the `%print-object-str` pair, the
dispatcher and the two methods. Judged payable: it buys every geom program a readable
solid and a printable scene graph, on an artifact already >120 KB. A program that does
NOT reference geom is byte-identical to a pre-change build (measured on
`(print (+ 1 2))` and on `(defstruct pt x y)`-plus-print, whose only delta is the
~130-175 B cycle guard the pretty-printer file owns). Pinned by
`GeomLibraryTest.aSolidPrintsItsLabelAndItsTwoCounts` /
`aNodeInASceneGraphPrintsItsChildCountInsteadOfOverflowingTheStack` /
`anAttachedSolidPrintsToo` / `aTransformAndABoundsStillPrintTheirSlots` and the
`geom-print-object-cross-backend` ci-spec case (all four backends, byte-identical).

The interpreter's lazy load has one ordering seam here: the printing operators take
their routing decision BEFORE evaluating the argument, so the first
`(print (geom:box ...))` of a session must load geom first --
`LispEvaluator.referencesGeom`, the twin of the torch pre-load beside it (torch and
geom are the only lazily loaded libraries with `print-object` methods).

## Reading a model file (2026-08-30; PLY and glTF/GLB 2026-08-31)

`geom:read-obj`, `geom:read-stl`, `geom:read-ply`, `geom:read-gltf` and the
dispatcher `geom:read-model` answer a `geom:solid` built through
`geom::%build-solid` -- the other half of `geom:polyhedron`'s own sentence, "for a
mesh that came from a file" -- except `read-gltf`, which answers the LIST of solids
its scene poses (the seam's list-answering arm; a single-mesh file is a list of
one, and `read-model` passes the shape through). They are the only members of the
package that open a file, and the reason they belong HERE rather than in a package
of their own is that everything they answer is this package's type: a
`mesh:read-obj` would be a package whose entire vocabulary is geom's.

### The seam a new format plugs into

Stated before the second reader was written; the fourth and fifth (PLY, glTF) are
what proved it -- each landed as exactly the four edits below, and the only thing
the seam did not carry was OUTSIDE it (the `JsonLibrary` splice order, in the
wiring table above):

- **A reader is `(path color label) -> a geom:solid`, or a LIST of them** for a format
  carrying several meshes. A list is what `scene:add` already splices and what
  `geom:triad` already answers, and a NODE HIERARCHY rides inside one: the solids are
  attached to a shared `geom:node`, so each one's `world-transform` carries its
  parents and the flat list still draws right. That is the mapping glTF's node tree
  took ("glTF" below), unchanged from this sentence as written before it landed.
- **The one internal representation is `geom::%build-solid`'s arguments**: a list of
  points, a list of index loops, a colour and a label. It is rich enough for OBJ, STL
  and PLY as they stand. It is NOT rich enough for glTF's per-primitive materials
  (one RGB per solid is all there is), and no format's per-vertex normals, texture
  coordinates or per-vertex colours survive it -- `geom` has no slot for any of them
  and facet normals are Newell's, computed from the geometry. A reader reads those
  records past rather than half-keeping them, and that is a decision, not an omission.
- **Numbers** come out of text through `geom::%scan-number` and out of binary through
  `read-sequence` over a packed buffer. Nothing else is shared and nothing else needs
  to be.
- **Dispatch is a `case`, deliberately not a table.** A Lisp-level registry of reader
  functions would make every reader reachable from the dispatcher, so a program
  reading one format would carry them all; a `case` keeps `LibraryDefunPruner` able to
  see which arm a program can reach. Five entries still do not earn a plugin
  framework, and the pruning is measured: a `read-ply` program carries neither
  `read-gltf` nor the JSON library
  (`GeomLibraryTest.aProgramReadingOnePlyCarriesNeitherGltfNorJson`).
- **Adding a format is four localized edits**: one reader defun, one
  `geom::%model-format` clause, one `geom:read-model` case arm, and a
  `PackageRegistry.GEOM_FUNCTIONS` entry for the public name.

### How a format is decided, and why the extension is last

`geom::%model-format` sniffs the file's own bytes (one 512-byte read) and falls back
to the extension only where no content test can answer:

| test | answer |
|---|---|
| opens with `glTF` | `:glb` |
| opens with `ply` + a line break | `:ply` |
| first token `solid` | `:stl` |
| first token `v`/`vn`/`vt`/`f`/`g`/`o`/`s`/`usemtl`/`mtllib` | `:obj` |
| first token opens with `{` | `:gltf` |
| otherwise | the extension |

**A binary STL has no magic number at all** -- a wart of the format -- so a binary
`.stl` whose 80-byte header is not the word `solid` is named by its extension, and
`:format` overrides everything. What the extension NEVER decides is the ASCII/binary
split, and that is the split that matters: both dialects are `.stl`.

All five answers are read now (`:gltf` and `:glb` share `geom:read-gltf`, which
re-sniffs the carrier itself). What survives of the refuse-by-name rule is
per-reader: the file-level refusal is only "cannot tell what format", and each
reader names what IT cannot carry ("PLY" and "glTF" below).

### The dialect test does not use `file-length`, and cannot

`file-length` answers **nil on both WASM backends by design** (no WASI filestat is
imported -- `doc/*/reference/functions/file-length.md`). The classic STL test is the
exact `84 + 50n` length arithmetic, and a reader resting on it classifies a file on
the JVM and TRAPS on wasm (measured: `(min 4096 (file-length in))` is
`min` of nil). So the dialect is decided by the file's SHAPE instead -- an ASCII file
opens with `solid` and carries `facet`/`endsolid` on its next line -- which is one
code path, identical on all four backends, and survives the trap the length test
exists for (`trimesh`'s own `unit_cube.STL` is BINARY with `solid unit_cube` in its
header). `read-sequence`'s short-fill answer is what replaces `file-length` for
"how much did I get", and it is identical on all four (verified 2026-08-30).

### Measured (2026-08-30, Apple M4 Max)

Real files: the Stanford bunny (`bunny-big.obj`, 35,947 v / 69,451 f, 2.4 MB), the
armadillo (49,990 v / 99,976 f, 4.6 MB OBJ and the same mesh as a 5.0 MB binary STL),
`trimesh`'s `featuretype.STL` (3,476 triangles) and the Utah teapot.

| stage | interpreter | JVM `.class` | wasm preview 1 |
|---|---|---|---|
| armadillo.obj parse (150k lines, 450k numbers) | 8,944 ms | **81 ms** | -- |
| bunny-big.obj parse (105k lines, 316k numbers) | 5,315 ms | 39 ms | 2,679 ms |
| `geom:polyhedron` of 50k points / 100k facets | 148 ms | 22 ms | -- |
| armadillo.stl binary read (100k triangles) | 498 ms | -- | -- |
| bun_zipper.ply ASCII read (3.0 MB, 35,947 v / 69,451 f) [2026-08-31] | 7,314 ms | 243 ms | 3,751 ms |
| cycloidal.ply binary read (1.08 MB, 21,384 v / 43,368 f) [2026-08-31] | 1,163 ms | 59 ms | 132 ms |
| Duck.glb read (120 KB, 2,399 v / 4,212 f) [2026-08-31] | 121 ms | 14 ms | 2 ms |
| Duck as base64-embedded .gltf (138 KB) [2026-08-31] | 985 ms | 81 ms | 22 ms |
| `geom:mesh` of 100k triangles (PRE-EXISTING) | ~5,200 ms | ~67 ms | -- |
| `geom:volume` of 100k triangles | 606 ms | 28 ms | -- |

Three things those numbers say:

- **The interpreter is the slow backend and it is still usable**: 9 s to load the
  biggest test file in the corpus, once, at load time. Compiled, it is 170 ms.
- **`geom:mesh` costs as much as parsing does** (~52 us a triangle on the
  interpreter). The loader did not introduce that and cannot fix it; it is the price
  of the cached mesh the whole renderer rests on, and it is paid once.
- **Rendering a loaded mesh is unaffected**: a 69,451-triangle bunny in
  `scene:offscreen` costs 1,075 ms for the frame that uploads the GPU buffers and
  **7 ms** for every frame after it. "No triangle is touched by Lisp during a frame"
  holds at 69k triangles exactly as it does at 13.8k.

**Text parsing has no faster spelling available.** Measured per float on the
interpreter: `read-from-string` 0.68 us, a whole parenthesised line through the reader
1.85 us, a hand-rolled `char-code` scan 20 us. The reader is 30x faster and is
UNUSABLE: it answers the SYMBOL `|1.30E-2|` for exponent notation on the WASM
backends, chokes on `#` and `|`, and reads `739/1` as a ratio -- and `json.lisp`
already declines it for the second reason, that it drags the runtime reader into
compiled output. `parse-integer` is no help either (5.2 us a call). So the scanner is
`char-code`, like `json.lisp`'s, and the JVM backend is where a big model gets loaded
fast.

**Two interpreter primitives are pathologically slow and were measured on the way
past**: `position` on a string is **27.6 us a call** (a 46-character string!) against
`subseq`'s 0.5 us and `char`'s 0.3 us, and `parse-integer` is 5.2 us. Both look like
generic-sequence dispatch in Lisp rather than a Java builtin. Neither is on the
readers' path any more, and neither has been investigated.

### What a real file taught, none of it a bug in the reader

- **A file carries its own units.** The Stanford bunny is 0.2 across (metres); a
  printable part is 200 (millimetres). `scene:fit` had a floor of 100 world units on
  the camera distance, the projection had `(max d 100.0)` in its frustum and the
  scroll clamp was `10 .. 200000` -- three absolute constants in a package that has no
  unit of length -- so a metre-scale model rendered as **zero pixels**. All three are
  now relative (`SceneOffscreenRenderTest.fitFramesASolidWhoseUnitsAreMetresRather
  ShrinkingItToADot` renders a box at four scales three decades apart). `scene:grid`'s
  600-unit default extent is NOT one of them: it is a documented keyword the caller
  sets, and `:extent nil` drops it.
- **`:solid` is the shading a dense mesh wants.** The default `:both` draws the
  wireframe over the triangles, which at 69,451 of them is a dark stipple.
- **Winding is the file's own.** `geom:volume` is the test, as it is for every
  constructor; a negative volume means the file is wound clockwise seen from outside,
  and the reader does not silently fix it.
- **Cross-format agreement is the parse's oracle.** The teapot read from an OBJ and
  from an ASCII STL answer the same volume (25.770105759541867) and area to all 17
  digits, and the armadillo read from an OBJ and from a binary STL written by
  `struct.pack('<f')` answer the same 237926.39344717923 -- so the `char-code` scanner
  produces the correctly-rounded float32 for all 450,000 numbers in that file.

### PLY (2026-08-31)

`geom:read-ply`, both `ascii` and `binary_little_endian`; `binary_big_endian` is
refused BY NAME, because the packed `read-sequence` path is little-endian by
contract (`binary-sequence-io.md`) and mis-reading every float would be strictly
worse. The header names every element, its count and every property with its
type, so the body is walked BY THE HEADER: `x`/`y`/`z` come from wherever the
vertex element put them, and everything else -- the bunny's `confidence` and
`intensity`, cycloidal's per-vertex AND per-face uchar colours -- is read past by
its declared width, never guessed. A file with no `face` element (the bunny's raw
range scans) answers its vertices with no facets.

A binary vertex block has three shapes, fastest first: all properties float32 ->
ONE `read-sequence` of `count*k` floats, columns sliced (`bun_zipper`'s shape);
float32 x y z FIRST with fixed-width extras -> one three-float read plus one skip
per row (cycloidal's 15-byte stride); anything else -> property-by-property
through `geom::%ply-scalar`, which folds a signed value's two's complement back
out of the unsigned packed read. A face is two transfers (one count, one bulk
index read), the STL reader's shape. Skipping is `geom::%skip-bytes` -- bounded
reads through a scratch buffer, because `file-position` answers nil by design on
this build, the same reason the STL dialect test cannot use `file-length`.

### glTF 2.0 / GLB (2026-08-31)

`geom:read-gltf`, both carriers: `.glb` (12-byte header, JSON chunk, BIN chunk)
and `.gltf` with `.bin` files beside it or base64 `data:` uris; a remote uri is
refused (a file reader does not fetch). The JSON goes through
`rontolisp:json-parse` -- which is why `JsonLibrary.process` moved OUTSIDE
`GeomLibrary.process` on both compile paths (wiring table above). Buffers are
exactly what the packed `read-sequence` was built for: a tight accessor is one
native transfer (POSITION, indices), a strided one (interleaved attributes; the
Duck) is a three-float read plus a skip per vertex.

The node hierarchy maps as the seam said it would: one glTF node -> one
`geom:node` posed by its TRS or matrix, one primitive -> one `geom:solid`
(coloured by `baseColorFactor`, labelled by mesh/node name) attached under its
node, the answer the FLAT LIST under one shared root. **A node's scale is baked
into vertices with `geom:nscale`** -- geometry, not pose ("Scaling" above) --
accumulated down the tree with each child's translation multiplied by the product
above it. That composition is EXACT for uniform scales; a non-uniform scale above
a rotated child would shear, which no rigid transform can carry, so it is refused
by name -- as is a node MATRIX whose columns are not orthogonal once the column
norms are out (shear again); a mirroring matrix moves its flip into a negative z
scale, which `nscale` carries by reversing the facets. Refused by name rather
than half-read, per the todo's list: `mode` other than 4, sparse accessors, any
`extensionsRequired` entry (Draco/meshopt arrive this way), skins, animations,
glTF 1.x. Verified on the Khronos corpus: Box.glb (volume exactly 1.0), Duck in
all three carriers (identical 1.1957991851442398, the 0.01 node scale baked so
the extent is 1.65 not 165), SimpleMeshes' two nodes landing side by side --
each rendered through `scene:offscreen` and looked at.

**The base64 path is the one place buffer bytes are assembled by Lisp
arithmetic** (base64 plus an IEEE-754 float32 decode -- `read-sequence` has no
stream to fill there), and it taught the one real lesson of the round: on the
compiled backends a large string is quadratic to BUILD through
`make-string` + `(setf (char))` (each write rebuilds the immutable string,
`string-write-runtime.md`) AND quadratic to SCAN as a mutable character vector
(each `(char s j)` renders the whole vector -- the todo on string mutability
carries the measured table). `geom::%utf8-string` first hit the build half: the
138 KB embedded Duck decoded in 1.1 s interpreted and **30.5 s** compiled. The
shape that escapes both halves is: build into a fill-pointered character array
(the one string shape `(setf (char))` writes in place everywhere,
`adjustable-arrays.md`), then ONE `subseq` on the way out so every later scan
reads an ordinary string. Same file: **82 ms** compiled. Any future library code
filling a large string must use that pair.

### Cost, pruning and the pin

Re-measured 2026-08-31, against that day's base `(print (geom:volume (geom:box
10)))` -- `.class` 137,917, `.wasm` 144,437, BYTE-IDENTICAL before and after the
PLY/glTF round (the moved `JsonLibrary` splices and prunes back out of a non-glTF
program):

| program calls | `.class` | delta | `.wasm` (P1) | delta |
|---|---|---|---|---|
| `read-stl` | 163,754 | +25,837 | 178,288 | +33,851 |
| `read-ply` | 199,792 | +61,875 | 217,905 | +73,468 |
| `read-gltf` | 246,096 | +108,179 | 266,353 | +121,916 |
| `read-model` (reaches all five) | 341,590 | +203,673 | 391,448 | +247,011 |

(The 2026-08-30 `read-stl` numbers -- 158,436 / 163,317 -- were stale by the 31st:
the drift arrived with other landings in between, measured identical with and
without the readers' change. `read-gltf`'s delta includes the JSON library.)
A program that reads no model file carries none of it -- verified by the absence of
the readers' error strings from `examples/browser/webgl-solids/solids.wasm` and
from a `(geom:volume (geom:box 10))` module, and by
`GeomLibraryTest.theReadersArePrunedFromAProgramThatReadsNoModelFile` /
`aProgramReadingOnePlyCarriesNeitherGltfNorJson`.

Pinned by twenty-odd `GeomLibraryTest` cases, the `geom-read-model-cross-backend`
ci-spec case (a box written out as an OBJ and as a binary STL and read straight
back: 1000.0 and 600.0 both ways, on all four backends), the
`geom-read-ply-gltf-cross-backend` case (both PLY dialects and a GLB written from
Lisp and read back -- the GLB's node both translates and scales, so 8000.0 is the
scale baked into vertices and `#f(10.0 0.0 0.0)` the pose that stayed rigid --
plus the big-endian refusal, verbatim) and
`SceneOffscreenRenderTest.aMeshReadOutOfAModelFileDrawsLikeAnyOtherSolid`.

### What is deliberately not here

- **WRITING any format.** `read-`/`write-` is the pair the naming leaves room for.
- **Vertex welding.** An STL solid carries three vertices per facet because the format
  has no index table; welding is a different operation, on a mesh from any source.
- **A byte-vector or stream entry point.** The browser can fetch bytes but cannot open
  a file, so a content-taking reader is the only one it could ever use -- and
  `read-sequence`'s packed fast path declines every in-memory stream, so a binary
  format read out of a byte vector would have to decode IEEE-754 in Lisp. The missing
  primitive is an in-memory byte stream the packed path accepts, not a second API.

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
`defclass` forms -- and, since todo-584, the two `defmethod print-object` forms -- are
unkeyed and stay roots, which is the type model plus the printed representation and
nothing more.
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
`abs`'d, and a point reflection leaves each triangle's normal unchanged while moving it
to the antipode. (Since todo-586 `geom:scale`/`geom:nscale` by `-1` no longer produce
that inverted mesh -- a mirroring factor flips the facets, "Scaling" above -- so an
inverted solid can only be built by hand through `geom:polyhedron`.) So the winding
check is the renderer's, not the modeller's, and the two agree by construction rather
than by measurement.

## Clicking: `scene:ray` and `scene:on-click` (2026-08-30)

A viewer that can be orbited but cannot say WHERE a click landed is half a
viewer, and it is the half `examples/macos/scene-robot-reach.lisp` needs. The
two names are one decision taken twice:

- **`scene:ray v x y` is the primitive, and it answers a LINE.** `(origin
  direction)`, world space, from view coordinates in points (AppKit's -- origin
  bottom-left, `+y` up). A pixel names a line through the world and which point
  of it was meant is the program's question; answering only a point would make
  the viewer decide something it has no business deciding, and a program wanting
  the ground plane could not undo it.
- **`scene:on-click v hook` is the convenience, and it answers a POINT** -- where
  that ray meets the plane through the ORBIT TARGET facing the camera. That is
  the one plane a viewer can pick without being told, and picking it is what
  makes "click where you see" true from any camera angle in one line. `nil`
  removes the hook; it is called on the main thread, like every other callback.

**A click is a press released without travelling more than 4 points**, measured
by `scene::%moved` accumulated over the drag. The deadzone is in the RELEASE arm
and deliberately not in the drag arm: the orbit those four points also performed
is invisible, whereas a drag that ignored its first four points would start with
a jump. So clicking and orbiting are one gesture and neither needs a modifier --
and a shift-drag (the pan) never produces a click at all. The hook is followed by
one `scene:refresh`, on the same reasoning the camera gestures redraw themselves:
an idle viewer must not answer a click with nothing on screen.

`scene:ray` is camera arithmetic and needs no window, no device and no contents,
so `SceneLibraryTest` drives it and `scene::%click-point` over a bare
`(make-instance 'scene:viewer-state :width ... :height ... :target ...)` -- the
centre pixel lands on the target, a pixel right of centre lands right of it on
the same plane, and `on-click` installs and removes. What stays uncovered is the
NSEvent that reaches them, exactly as `scene::%view-point` does.

**`scene:on-click` is where the IK divergence in the example was found, and the
finding belongs here** because it is about `geom`'s type model, not about that
program. A chain posed by solving for a WORLD-frame angular velocity has to carry
it back into each joint's frame as `Rp^T Rot(w) Rp . R`, and that sandwich
DOUBLES the parent's orthogonality error into the child every time it runs: eight
solver iterations a frame down a five-joint chain is 2^8 a frame, and the
measured error went 1e-16 -> 1e-2 in five frames, with links stretching by tens
of units. Stating the Jacobian in each joint's OWN frame -- block `M . R` with
`R` the joint's world rotation, update `R . Rot(w)` -- removes the sandwich, and
the drift falls back to arithmetic (about 1e-8 a frame). It is also 1.5x faster.
`geom:rotation-of` slots are float32 by the package's own rule, so this is a
constraint on any consumer that composes rotations, not a quirk of one example.

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
