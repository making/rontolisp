# The `geom` package (solid modeling)

One hand-written Lisp library, `src/main/resources/am/ik/rontolisp/eval/geom.lisp`,
following the `linalg.lisp` / `appkit.lisp` pattern (`linalg.md`, `objc.md`) so one
implementation runs identically on every backend: rigid `geom:transform` values, a
scene-graph `geom:node`, boundary-represented `geom:solid`s with a cached model-space
triangle mesh, nine primitive constructors plus `triad`, the measurements (`bounds`,
`volume`, `centroid`, `surface-area`), the booleans and five model-file readers. 63
exported functions plus `geom:*tolerance*` and four CLOS class names.

**The MODELLING half reaches for nothing but `linalg`** -- no `objc:`, no `java:`, no
`SourceLoader`, so the same solids tessellate in the browser playground and in a `.wasm`.
`scene` (the macOS viewer) is a CONSUMER of this package.

**The one exception is the five READERS** (`read-obj` / `read-stl` / `read-ply` /
`read-gltf` / `read-model`): ANSI CL I/O on all four backends, dropped by
`LibraryDefunPruner` from every program that does not call one. Nothing else here may do
I/O. The interpreter and the JVM backend run Java kernels over some of them; the defun is
still the definition.

## Wiring (the `linalg` pair, exactly)

| piece | where |
|---|---|
| source | `eval/geom.lisp` |
| splice class | `eval/GeomLibrary` (`forms()` parsed once and cached, `process(program)`, `isGeomQualified`, `mentionsGeomClass`, `CLASS_NAMES`) |
| package | `PackageRegistry.GEOM_FUNCTIONS` + `LispNames.GEOM_PKG` + `geomFunctionNames()` + `BUILTIN_PACKAGE_NAMES` |
| interpreter | `LispEvaluator.ensureGeomLoaded()` on the first `geom:`-qualified FUNCTION resolution, plus `ensureGeomClassesFor(form)` at the seven `ensureAsdfClassesFor` sites |
| compile path | `cli/CompileFrontend`: `GeomLibrary.process` INSIDE `LinalgLibrary.process` (beside `TorchLibrary`); `JsonLibrary.process` OUTSIDE both, since `geom:read-gltf` parses through `rontolisp:json-parse`. The browser playground repeats the nesting. |
| pruning | `LibraryDefunPruner.prunableNames()` collects `GeomLibrary.forms()` |
| interpreter natives | `eval/GeomKernels.install`, from `ensureGeomLoaded` right after the forms are evaluated |
| JVM kernels | `codegen/jvm/JvmGeomKernelCompiler` (call site) + `JvmGeomTemplate` (embedded bridge) + `JvmGeomRuntimeBuilder` (injection), gated in `JvmLispCompiler` on `gateMembers()`, one `JvmExprCompiler.compileCons` case |
| docs | `doc/{en,ja}/guides/solid-modeling.md`, 63 pages under `reference/functions/geom-*.md` |

**Trap -- the class-mention trigger is not optional.** The lazy load fires on FUNCTION
resolution, but a `defmethod` specializer, `typep`, `typecase`, `make-instance` or a
`defclass` superclass can name `geom:solid` without calling any geom function, and would
see no such class and quietly answer `nil`. That is `GeomLibrary.mentionsGeomClass` /
`LispEvaluator.ensureGeomClassesFor` (the `AsdfRuntimeLibrary.mentionsComponentClass`
twin); a new geom class must be added to `GeomLibrary.CLASS_NAMES`. The compile path needs
no equivalent -- its splice fires on any `geom:` symbol anywhere, quoted data included.

**Trap -- a library-internal helper may not be named `%make-<class>`.** `expandDefclass`
generates constructors as `PKG::%make-<member>`, so a hand-written `geom::%make-solid`
SILENTLY replaced `geom:solid`'s constructor ("Unknown keyword argument: :VERTICES"). The
builder is `geom::%build-solid`.

## The type model

- **`transform` is a VALUE, not a superclass**: translation 3-vector + 3x3 rotation,
  read-only slots. `compose` / `invert` / `transform-point` / `inverse-transform-point`
  build new ones, and so do the node mutators -- `translate` / `rotate` / `place` /
  `reorient` REPLACE `geom::%local` with a fresh transform rather than assigning into the
  old one, so one transform can be handed to several nodes safely.
- **`node` HAS a transform rather than being one.** `world-transform` is memoized in
  `geom::%world`; `geom::%stale` drops it down the WHOLE subtree on every pose change.
  Every node slot is internal (`%local` / `%parent` / `%children` / `%world`) and the
  public readers are plain defuns, so no `setf` can leave the memo stale.
- **`solid` is a node carrying a boundary representation.** `vertices` is ONE rank-2
  `(n 3)` packed array of MODEL coordinates, which makes a whole-solid transform a single
  `linalg:matmul` (`%solid-bounds` is the one place that does it); `facets` is a list of
  index loops.
- **`:frame` is a keyword, not a positional flag**; `:local` is the default.
- **float32 everywhere**: every array is `:element-type 'single-float`, the literal spelled
  at each `make-array` so all four backends pick `float[]` (TYPE_F32ARR) statically. A
  packed single-float array IS a GPU vertex buffer's bytes and `objc:data` takes one of any
  rank, so `geom:mesh` -> `objc:data` -> `setVertexBytes:` needs no conversion
  (`objc.md`, "Metal").

## The winding convention

Each facet is an index loop wound **counter-clockwise seen from OUTSIDE**. `volume`
integrates the divergence theorem over the mesh triangles, so a mis-wound facet SUBTRACTS:
`volume` is the winding test for every constructor. A tessellated primitive is INSCRIBED in
its smooth ideal, so measured volume converges from below. These integers are ci-spec
expectations; a shift is a tessellation change or a bug:

| solid | volume | closed form |
|---|---|---|
| `(box '(100 200 300))` | 6000000.0 | exact (area 220000 exact too) |
| `(cylinder :radius 50 :height 100 :sides 64)` | 784137 | 785398 |
| `(sphere :radius 50 :sides 32 :stacks 24)` | 518015 | 523599 |
| `(torus :radius 60 :tube 20 :sides 48 :rings 24)` | 467012 | 473741 |
| `(cone :radius 50 :height 120 :sides 64)` | 313655 | 314159 |

**Trap -- `volume` is the winding test, not the whole mesh's test.** `geom:revolution` used
to cap BOTH ends of a profile whose ends are the same point (a torus's closed
cross-section); the two coincident discs wind opposite ways, so the divergence integral
cancelled them and volume stayed right while `surface-area` counted both and a torus
rendered as a filled disc. The rule is `geom::%closed-profile`: a closed profile gets
neither cap. The pins assert the AREA (47,155 rounded) and the FACET COUNT
(1,152 = `sides * rings`), which a cancelling pair cannot hide behind.

## The cached mesh -- the load-bearing invariant

`geom:mesh` answers the solid's triangles in MODEL space: 18 floats a triangle (three
corners of position + normal), fan-triangulated per facet with a Newell normal, computed
once into `geom::%mesh`. `geom:wireframe` (each edge once, 6 floats a segment) into
`geom::%wire`.

A rigid solid's triangles never change, only its pose does. On a 30-joint chain (60 solids,
13,800 triangles), transforming every vertex into world space every frame is 380 ms/frame;
the cached model-space mesh plus one 4x4 matrix per solid is **9.0 ms**. So `geom:mesh` is
PUBLIC surface and **a renderer must touch no triangle during a frame**.

`geom:nscale` is the only vertex mutation and therefore the only place that must
invalidate: it drops `%mesh`, `%wire` AND `user-data`. **Any future vertex mutation must
call `geom::%invalidate-mesh`.**

`user-data` is a slot a consumer hangs its own state on (a renderer keeps its GPU buffers
there). **It is a slot because a hash table cannot key on a node**: `:test 'eq` is accepted
and IGNORED, so a table compares keys STRUCTURALLY (`hash-tables.md`), and two sibling
nodes with equal slots -- routine in a scene graph -- collide into ONE entry.

## Scaling and the package's verb convention

`translate`/`rotate` ACCUMULATE onto a node's local transform; `place`/`reorient` SET it --
POSE mutators, so plain verbs. Scaling rewrites a `solid`'s MODEL vertices, so it follows
CL's functional/destructive convention. **The package RULE: a GEOMETRY operation builds by
default and offers an `n`-spelling for in-place; a pose or graph mutator (`attach`,
`detach`, `place`...) does neither.**

- **`geom:scale` is functional**: a new UNATTACHED root solid with scaled vertices, the
  facets, color and label, and `(:scale s factor)` in its `history`. Parent, children and
  `user-data` are not carried.
- **`geom:nscale` is in-place** and the package's ONLY vertex mutation.
- The factor may be a number or a 3-vector/list; the mesh is rebuilt from the facets with a
  fresh Newell normal per triangle.
- **A mirroring factor (negative determinant -- an odd number of negative components) FLIPS
  the facets**, reversing each loop so the winding stays outward. Volume cannot see this
  (the integral is `abs`'d), so the pin is the mesh normal of a mirrored box's `+x` face. A
  ZERO component is refused, naming the function and the factor.
- **The transform stays RIGID -- no scale slot on `geom:transform` or `geom:node`,
  settled.** `volume`, `centroid` and `surface-area` are computed from the MODEL-space mesh
  and know nothing of the node's transform, so a scaled NODE would silently report unscaled
  measurements; `%world-polygons`, `%solid-bounds`, the per-draw model uniform in
  `metal.lisp`/`scene.lisp` and `invert` would each need the scale threaded through.

## The printed representation

`defmethod print-object` on exactly TWO of the four classes:

- **`geom:solid`** -- `#<GEOM:SOLID "b" 8 vertices 6 facets>`: the label when there is one
  (prin1-quoted so it cannot be misread as a count) plus the two counts.
- **`geom:node`** -- `#<GEOM:NODE 1 child>` / `#<GEOM:NODE 2 children>`: the child count
  and NOTHING that walks the graph. `parent` and `children` point at each other, so after
  `(geom:attach a b)` the default renderer recursed into a `StackOverflowError`. The
  renderer's cycle guard (`.kb/pretty-printer.md`) makes that finite now, but `#` markers
  are a fallback, not a representation.
- **`geom:transform` and `geom:bounds` deliberately have NO method**: their slots ARE the
  value, they hold no cache, they cannot cycle.

Both use `print-unreadable-object :type t`, so `princ` drops the package qualifier
(`.kb/clos.md`). A `defmethod print-object` in a spliced library turns on the printer route
(`printObjectTags`, `.kb/clos.md`) for EVERY program that splices geom, on all three
compile backends (+9-13%); a program that does NOT reference geom is byte-identical.

**Ordering seam:** the printing operators route BEFORE evaluating the argument, so the first
`(print (geom:box ...))` of a session must load geom first -- `LispEvaluator.referencesGeom`,
the twin of the torch pre-load beside it (torch and geom are the only lazily loaded
libraries with `print-object` methods).

## Reading a model file

`geom:read-obj`, `read-stl`, `read-ply`, `read-gltf` and the dispatcher `geom:read-model`
answer a `geom:solid` built through `geom::%build-solid` -- except `read-gltf`, which
answers the LIST of solids its scene poses (`read-model` passes the shape through).

### The seam a new format plugs into

- **A reader is `(path color label) -> a geom:solid`, or a LIST of them.** A node HIERARCHY
  rides inside a list: the solids are attached to a shared `geom:node`, so each one's
  `world-transform` carries its parents and the flat list still draws right.
- **The one internal representation is `geom::%build-solid`'s arguments**: points, index
  loops, a colour, a label. NOT rich enough for glTF's per-primitive materials (one RGB per
  solid), and no per-vertex normals, texture coordinates or per-vertex colours survive it --
  facet normals are Newell's. A reader reads those records past.
- **Numbers** come out of text through `geom::%scan-number`, out of binary through
  `read-sequence` over a packed buffer.
- **Dispatch is a `case`, deliberately not a table**: a Lisp-level registry would make every
  reader reachable from the dispatcher, so a program reading one format would carry them
  all; a `case` keeps `LibraryDefunPruner` able to see which arm is reachable.
- **Adding a format is four localized edits**: one reader defun, one `geom::%model-format`
  clause, one `geom:read-model` case arm, one `PackageRegistry.GEOM_FUNCTIONS` entry.

### How a format is decided

`geom::%model-format` sniffs the file's own bytes (one 512-byte read), falling back to the
extension only where no content test can answer: opens with `glTF` -> `:glb`; opens with
`ply` + a line break -> `:ply`; first token `solid` -> `:stl`; first token
`v`/`vn`/`vt`/`f`/`g`/`o`/`s`/`usemtl`/`mtllib` -> `:obj`; first token opens with `{` ->
`:gltf`; otherwise the extension.

**A binary STL has no magic number at all**, so a binary `.stl` whose 80-byte header is not
the word `solid` is named by its extension; `:format` overrides everything. What the
extension NEVER decides is the ASCII/binary split -- both dialects are `.stl`. `:gltf` and
`:glb` share `geom:read-gltf`, which re-sniffs the carrier. The file-level refusal is only
"cannot tell what format"; each reader names what IT cannot carry.

**The dialect test does not use `file-length`**, though it answers on all four backends now
(`fd_filestat_get` is the twelfth preview1 import). Deciding the dialect from the file's
SHAPE (an ASCII file opens with `solid` and carries `facet`/`endsolid` on its next line) is
one code path on all four and survives files the length test does not (`trimesh`'s
`unit_cube.STL` is BINARY with `solid unit_cube` in its header). `read-sequence`'s
short-fill answer replaces `file-length` for "how much did I get".

### Parsing costs

**Text parsing has no faster spelling available.** Per float on the interpreter:
`read-from-string` 0.68 us, a parenthesised line through the reader 1.85 us, a hand-rolled
`char-code` scan 20 us. The reader is 30x faster and UNUSABLE: it answers the SYMBOL
`|1.30E-2|` for exponent notation on the WASM backends, chokes on `#` and `|`, reads `739/1`
as a ratio, and drags the runtime reader into compiled output. `parse-integer` is no help
(5.2 us a call). So the scanner is `char-code`, like `json.lisp`'s.

Two interpreter primitives are pathologically slow, neither on the readers' path and neither
investigated: `position` on a string is **27.6 us a call** against `subseq`'s 0.5 us and
`char`'s 0.3 us; `parse-integer` is 5.2 us. Both look like generic-sequence dispatch in Lisp
rather than a Java builtin.

### The Java kernels (interpreter and JVM backend)

Four file-scaled members are accelerated on both:

| member | what the kernel does |
|---|---|
| `geom:read-obj` | the whole line walk and number scan, packing into the `(n 3)` `single-float` array |
| `geom:mesh` | the fan triangulation and Newell's normal per facet |
| `geom:wireframe` | the edge walk, over an open-addressing `long` set instead of an `equal` hash table of conses |
| `geom::%vertex-extremes` | the posed min/max, without materializing the transformed array |

**Interpreter:** `eval/GeomKernels` uses `LinalgSimd`'s interception seam verbatim
(`.kb/linalg-simd.md`) -- `Environment.defineFunction` over the defun just evaluated, each
native a PARTIAL function answering Java `null` for an input it declines, the wrapper then
applying the captured defun.

**JVM:** `codegen/jvm/JvmGeomKernelCompiler` is the `geom:` sibling of
`JvmLinalgKernelCompiler` (`.kb/linalg-blas.md`) -- a CALL-SITE compiler that evaluates the
argument forms once into temps, calls into an embedded bridge (`JvmGeomTemplate`, injected
the way `JvmBlasTemplate` is, `.kb/template-class-embedding.md`) and falls through an
`IFNONNULL` to the spliced defun over the same temps.

**Both are ALWAYS ON, and `--simd` is not the precedent.** `--blas`/`--simd` are opt-in
because a vendor gemm and a lane reduction REASSOCIATE. Nothing here reassociates: every
step is the defun's step transcribed -- `%scan-number`'s mantissa accumulated in `double`
and scaled once by `Math.pow`, Newell's normal accumulated in `double` over widened `f32`
reads, `%unit`'s normalization through the same narrow-then-widen chain `emap`/`sum`/`mul`
uses, the extremes walk narrowing exactly where `%la-matmul` and `%la-bcast` narrow. **A
kernel that rounds differently is a bug, not a tolerance.** Both have a
`setGeomKernels(false)` oracle switch (`LispEvaluator`, `JvmLispCompiler`, package-private)
so `GeomKernelsTest` and `JvmGeomKernelCompilerTest` run every fixture down both paths and
compare PRINTED values; `geom-read-model-cross-backend` pins them against the two WASM
backends, which have no kernel.

Three `geom.lisp` splits made the seam possible (a seam has to be a whole function to be
replaceable):

- **`geom::%solid-of-vertices`** -- the half of `%build-solid` past the packing, taking a
  vertex ARRAY, so a reader need not build a million three-element lists for
  `linalg:from-list` to walk back.
- **`geom::%vertex-extremes`** -- `%solid-bounds`'s `let*`, which posed every vertex through
  `linalg:matmul` into an array read once and thrown away.
- **`geom::%model-extent`** -- the diagonal of the box a solid's own vertices span, no
  transform. `scene.lisp`'s `%gpu-buffers` calls it instead of the `linalg:amax`/`amin`
  pair; it is the ONE `geom::` internal `scene.lisp` reaches for, because the public bounds
  API is world-space only.

On a 155 MB scanned hand (1.06M v / 2.12M f), read+mesh+wireframe+bounds+extent goes
539,577 ms -> 1,193 ms interpreted and 6,999 ms -> 1,277 ms compiled, all paths printing the
same bounds to the last digit. NOT accelerated: `read-stl`, `read-ply`, `read-gltf` (the
ASCII PLY is 7.3 s for a 3 MB file and is the next one worth doing) and every modelling verb.

JVM-only mechanics:

- **The gate is the CALL SITE, not the splice**: `JvmLispCompiler` scans the ALREADY-PRUNED
  program for `JvmGeomKernelCompiler.members()` (+16.8 KB where it arms; a program calling
  none of them is byte-identical). **`--dynamic` is excluded outright** -- it skips the
  pruner, and its point is that a call site honours a definition replaced at run time, which
  a kernel over the defun would not.
- **All four members arm the bridge.** `geom::%vertex-extremes` used to be accelerated
  without arming anything, because `LibraryDefunPruner` counted a `defclass` header's own
  name as a function reference and `geom:bounds` is both a `defclass` and a `defun`; the
  pruner now walks a class header by POSITION (`.kb/library-defun-pruning.md`).
- **`geom:read-obj` is the one member whose accelerated answer is not the member's answer.**
  The bridge scans the file into the packed `(n 3)` array and the index loops and hands
  those to the LISP `geom::%solid-of-vertices`; the colour default, the identity transform
  and the `make-instance` stay in Lisp. The `:color`/`:label` tail is built into a rest list
  ONCE and read by whichever variadic defun runs, which works because the reader and the
  builder declare the same two keywords; a tail that is not those literal keywords declines
  at COMPILE time so the defun's own lambda list still signals about it.
- **The bridge can decline to exist.** A template carries the project's class version, so an
  older JRE would answer `UnsupportedClassVersionError` from `Lookup.defineClass`.
  `_geomInit` catches `LinkageError`, leaves `_geomAvailable` false and says nothing, and
  every call site tests `_geomReady()` first. The compiled output's JRE floor is unchanged.

WASM has no equivalent (no `float[]` to pack into without the GC array types; Preview 1 reads
through its own stream layer).

### What a real file taught

- **A file carries its own units.** The Stanford bunny is 0.2 across (metres); a printable
  part is 200 (millimetres). `scene:fit`'s 100-world-unit camera floor, the projection's
  `(max d 100.0)` frustum and the `10 .. 200000` scroll clamp made a metre-scale model
  render as zero pixels; all three are now relative. `scene:grid`'s 600-unit default extent
  is NOT one of them -- a documented keyword, and `:extent nil` drops it.
- **`:solid` is the shading a dense mesh wants**; the default `:both` draws the wireframe
  over the triangles, a dark stipple at 69,451 of them.
- **Winding is the file's own.** A negative `geom:volume` means clockwise-from-outside; the
  reader does not silently fix it.
- **Cross-format agreement is the parse's oracle**: the teapot from an OBJ and from an ASCII
  STL answer the same volume (25.770105759541867) and area to all 17 digits; the armadillo
  from an OBJ and from a binary STL answer the same 237926.39344717923.

### PLY

`geom:read-ply` reads `ascii` and `binary_little_endian`; `binary_big_endian` is refused BY
NAME, because the packed `read-sequence` path is little-endian by contract
(`binary-sequence-io.md`). The header names every element, count and property with its type,
so the body is walked BY THE HEADER: `x`/`y`/`z` come from wherever the vertex element put
them, everything else is read past by its declared width, never guessed. A file with no
`face` element answers its vertices with no facets.

A binary vertex block has three shapes, fastest first: all properties float32 -> ONE
`read-sequence` of `count*k` floats, columns sliced; float32 x y z FIRST with fixed-width
extras -> one three-float read plus one skip per row; anything else -> property-by-property
through `geom::%ply-scalar`, which folds a signed value's two's complement back out of the
unsigned packed read. A face is two transfers (one count, one bulk index read). Skipping is
`geom::%skip-bytes` -- bounded reads through a scratch buffer, because `file-position`
answers nil by design on this build.

### glTF 2.0 / GLB

`geom:read-gltf`, both carriers: `.glb` (12-byte header, JSON chunk, BIN chunk) and `.gltf`
with `.bin` files beside it or base64 `data:` uris; a remote uri is refused. The JSON goes
through `rontolisp:json-parse` -- why `JsonLibrary.process` sits OUTSIDE
`GeomLibrary.process`. A tight accessor is one native `read-sequence` transfer; a strided
one is a three-float read plus a skip per vertex.

One glTF node -> one `geom:node` posed by its TRS or matrix; one primitive -> one
`geom:solid` (coloured by `baseColorFactor`, labelled by mesh/node name) attached under its
node; the answer is the FLAT LIST under one shared root. **A node's scale is baked into
vertices with `geom:nscale`** -- geometry, not pose -- accumulated down the tree with each
child's translation multiplied by the product above it. EXACT for uniform scales; a
non-uniform scale above a rotated child would shear and is refused by name, as is a node
MATRIX whose columns are not orthogonal once the column norms are out. A mirroring matrix
moves its flip into a negative z scale, which `nscale` carries by reversing the facets. Also
refused by name rather than half-read: `mode` other than 4, sparse accessors, any
`extensionsRequired` entry (Draco/meshopt arrive this way), skins, animations, glTF 1.x.
Verified on the Khronos corpus (Box.glb volume exactly 1.0; Duck identical in all three
carriers at 1.1957991851442398, the 0.01 node scale baked so the extent is 1.65 not 165).

**The base64 path is the one place buffer bytes are assembled by Lisp arithmetic** (base64
plus an IEEE-754 float32 decode -- `read-sequence` has no stream to fill there). The shape to
use when filling a large string: build into a fill-pointered character array (the one string
shape `(setf (char))` writes in place everywhere, `adjustable-arrays.md`), then ONE `subseq`
on the way out. On the 138 KB embedded Duck that is 82 ms compiled against 30.5 s. Both
quadratic halves that forced it are now CLOSED (`string-index-cost.md`,
`string-write-runtime.md`), so the pair is a plain idiom, not a required workaround.

### Reader cost and pruning

Against a base `(print (geom:volume (geom:box 10)))` of `.class` 137,917 / `.wasm` 144,437:
`read-stl` +25,837 / +33,851; `read-ply` +61,875 / +73,468; `read-gltf` +108,179 / +121,916
(includes the JSON library); `read-model` (reaches all five) +203,673 / +247,011. A program
that reads no model file carries none of it -- verified by the absence of the readers' error
strings from `examples/browser/webgl-solids/solids.wasm`.

`geom-read-model-cross-backend` writes a box out as an OBJ and as a binary STL and reads it
straight back (1000.0 and 600.0 both ways). `geom-read-ply-gltf-cross-backend` covers both
PLY dialects and a GLB written from Lisp and read back -- the GLB's node both translates and
scales, so 8000.0 is the scale baked into vertices and `#f(10.0 0.0 0.0)` the pose that
stayed rigid -- plus the big-endian refusal, verbatim.

### Deliberately not here

- **WRITING any format.** `read-`/`write-` is the pair the naming leaves room for.
- **Vertex welding.** An STL solid carries three vertices per facet because the format has
  no index table; welding is a different operation, on a mesh from any source.
- **A byte-vector or stream entry point.** `read-sequence`'s packed fast path declines every
  in-memory stream, so a binary format read out of a byte vector would decode IEEE-754 in
  Lisp. The missing primitive is an in-memory byte stream the packed path accepts, not a
  second API.

## Boolean operations (union / difference / intersection / section)

**The algorithm is BSP-tree clipping -- the csg.js formulation.** Each operand's world-space
boundary polygons go into a binary space partition, the two trees clip each other, and the
surviving fragments are the result's boundary (`geom::%bsp-*`, ~250 lines). It has NO
per-degeneracy special cases -- one epsilon in `geom::%split-polygon` classifies every
point, so coplanar faces, a vertex/edge on a face, exact touching and a through-hole with
coplanar caps are one code path. Its weakness (it fragments faces that did not need
splitting) costs nothing here. Revisit only if face structure must survive (feature naming,
exact face counts).

- **The pipeline runs in float64 and narrows on the way out.** Scalar arithmetic is double
  and an `aref` of a packed float32 vertex WIDENS, so the pipeline points (plain `(x y z)`
  lists) carry 29 more bits than the data; the result narrows once in `%build-solid`'s
  vertex array.
- **`geom:*tolerance*` is RELATIVE** (default 1.0e-5): the classification epsilon is
  `(* geom:*tolerance* extent)`, extent the largest side of the operands' combined world
  bounds (`geom::%operand-epsilon`). geom has no unit of length, so an absolute epsilon
  cannot serve a 0.001-scale and a 1000-scale model at once.
- **Operands in WORLD coordinates, untouched; the result is a new ROOT solid** with
  world-coordinate vertices and an identity local transform. `(geom:history result)` answers
  `(op a b)` (the `history` slot, nil for primitives).
- **Result vertices are welded on the epsilon grid** (`geom::%weld-key` in
  `geom::%csg-solid`): a shared edge split from both sides lands on one key even when the
  two interpolations differ in the last bits, so the shell closes. Winding survives clipping
  (an inverted polygon is reversed), so `volume` stays the winding check on the RESULT; the
  volume oracle `vol(A u B) + vol(A n B) = vol(A) + vol(B)` is also the normals test.
- **An empty result is an EMPTY solid** (zero-row vertex array, no facets, volume 0.0), not
  an error.
- **`geom:section`** is the same classification with one operand trivial: per-facet plane
  segments (`geom::%facet-section`, oriented along plane-normal x facet-normal so outer loops
  wind counter-clockwise seen from +normal and holes clockwise), stitched on the weld grid
  into closed loops; an unclosed chain (a tangent touch) is dropped rather than answered
  broken. A facet lying IN the plane is skipped; its boundary comes from its neighbours'
  edges.

## Pruning and cross-backend parity

Every geom definition is a `defun`/`defconstant`, so `LibraryDefunPruner` keys it by name
and the fixpoint reaches only what the program calls; the four `defclass` forms and the two
`defmethod print-object` forms are unkeyed roots.
`(print (geom:volume (geom:box 10)))` carries 16 geom methods (vec3, %unit,
%identity-rotation, axis-vector, axis-angle-matrix, rpy-matrix, make-transform,
%build-solid, %solid-of-vertices, mesh, %facet-normal, box, volume and the generated
accessors) and none of cylinder / cone / sphere / torus / revolution / extrusion /
wireframe / surface-area / centroid.

**Trap (fixed): a `defclass` and a `defun` of the same name used to keep each other alive.**
`geom:bounds` is the package's one name that is both, and the class form is a root that
spelled it -- so every geom program, `(print (geom:vec3 1 2 3))` included, also carried
`bounds`, `%solid-bounds`, `%vertex-extremes`, `bounds-union`, `compose` and
`world-transform`. `LibraryDefunPruner` now walks a class header by POSITION
(`.kb/library-defun-pruning.md`).

`geom-solids-cross-backend` and `geom-transforms-cross-backend` print exact answers (a box's
volume and area, its bounds and centroid, a prism's 120.0, the poses that land on integers)
verbatim -- pinning the packed single-float printer too -- while every trigonometric answer
is scaled and ROUNDED so a float32 last bit cannot fail the case.

## The arrow and the origin indicator

`geom:arrow` is a shaft and a pointed head as ONE solid; `geom:triad` is three of them -- +x
red, +y green, +z blue, labelled `"x"` / `"y"` / `"z"` -- as a LIST of solids. They replace
the origin indicator the viewer drew out of `metal:+line+` segments (`scene::%build-axes`,
scaled by `0.16 * distance`), which had no width.

**Built directly, not `union`-ed**: the seam is known at construction time, and the arrow is
the one solid a program may build several of per frame's worth of furniture. The shell is a
base cap, `n` shaft quads, `n` quads of the head's underside annulus and `n` head triangles:
`3n + 1` facets, 142 triangles at the default 24 sides.

**Its volume is exact, which is what pins the winding.** The closed form is the shape
actually built -- a prism plus a pyramid on the same regular n-gon,
`(n/2) sin(2pi/n) * (r^2 * (len - head) + hr^2 * head / 3)` -- so
`(geom:arrow :length 200 :sides 24)` is `32201.23...` exactly.

- **A constructed arrow does NOT scale with the view distance, and the viewer's line triads
  still do.** A solid has a size in world units; a `geom` that asked the camera anything
  would no longer be backend-independent. The auto-scaled behavior stays on `scene:axes`.
  `geom:triad`'s default length is **200** (what `0.16 * distance` draws at the viewer's
  default distance of 1200, rounded); `geom:arrow`'s own default length is `1.0`, and every
  other measurement it takes is a fraction of the length.
- **`scene:axes`' `:bodies` and `:both` are unchanged**: a per-body triad is sized from that
  body's own model extent (`scene::%gpu-buffers`) and drawn under the body's world transform.
- **The triad is a `geom` function returning solids, not a viewer mode**; `:at` places all
  three, and it returns a list, which `scene:add` splices.
- **`scene:axes`' initform is `nil`.** The modes all remain, so `scene::%build-axes` and the
  unit line buffer survive.

## What `scene:add` accepts

**Every argument is a solid or a LIST of solids, spliced in order**, and anything else is
refused THERE, naming it. `(scene:add *v* (geom:triad))` used to cons the list itself into
`scene::%contents` and complain one frame later from inside the draw callback as `No
applicable method: GEOM:USER-DATA on CONS`. `scene::%check-solid` runs over every argument
BEFORE the first one is consed in, so a refused call leaves the viewer as it was.
`scene:drop` takes the matching shape. `nil` is the empty list and adds nothing; not an
error. `SceneLibraryTest` needs no display: `scene:viewer-state`'s contents slot has an
initform, so `(make-instance 'scene:viewer-state)` is a viewer as far as `add`/`drop` care.

## The renderer: `metal` and `scene`

`eval/metal.lisp` + `eval/MetalLibrary` and `eval/scene.lisp` + `eval/SceneLibrary`, wired
exactly as geom is, with three differences:

- **They are macOS-only.** Both bottom out in `objc:send`, so `CompileFrontend` refuses a
  `.wasm` output naming the reference -- `AppKitLibrary.firstObjcReference` answers for all
  four macOS packages (`objc`, `appkit`, `metal`, `scene`), which is why it lives there and
  not in one library per package.
- **The splice chain runs innermost first:** `SceneLibrary` before `MetalLibrary` before
  `GeomLibrary`/`LinalgLibrary` before `AppKitLibrary`, so each pass sees the references the
  previous one introduced (`scene` names `geom:`, `metal:`, `linalg:`, `appkit:`;
  `metal:run`'s clock is `appkit:timer`).
- **`metal` must stay usable without `geom` or `scene`** -- the four
  `examples/macos/metal-*.lisp` drive it directly (`.kb/objc.md`, "Metal").

Thread facts are `.kb/objc.md`'s: `appkit:timer` is the clock, every hop is `objc:on-main`
(inline when already on thread 0), and a callback runs on thread 0 with the interpreter's
GLOBAL dynamic bindings.

**No triangle is touched by Lisp during a frame**, and `scene.lisp` is built for it:

- each solid's `geom:mesh` and `geom:wireframe` go into `MTLBuffer`s of their own the FIRST
  time the solid is drawn, and the entry -- `(mesh-buffer tri-count wire-buffer
  segment-count axis-length)` -- lives in `geom:user-data`, not a table keyed by the solid;
- the vertex function takes `vp` and `model` as SEPARATE uniforms and transforms the normal
  by `model` too, so a solid that moves needs no re-upload and a frame's whole CPU cost is
  one 4x4 matrix and one draw call per solid;
- lines (grid, axis triads, every wireframe) go through a second pipeline with `metal:+line+`
  (`MTLPrimitiveTypeLine` = 1).

- **The callbacks are keyed by VIEW, not by an `*active*` global.** AppKit's callbacks are
  process-wide, so one `objc:define-class "RontoLispSceneView"` serves every viewer and
  `scene::*views*` maps `(objc:address view)` -> viewer (`appkit::*actions*` keyed by widget
  address is the precedent). Two viewers orbit independently.
- **Resize follows the window.** The view posts `NSViewFrameDidChangeNotification` to one
  shared observer -- **`setPostsFrameChangedNotifications:` is not optional**, without it
  NSView posts nothing -- which finds the viewer by the notification's object and calls
  `metal:resize` plus a redraw.
- **The camera gestures redraw themselves and the mutators do not.**
  `scene::%on-mouse-dragged` / `%on-scroll` / `%on-frame-changed` call `scene:refresh`;
  `scene:add` / `camera` / `grid` / `shading` / `axes` do not.

`MetalLibraryTest` and `SceneLibraryTest` cover the library as a LIBRARY (public names match
the registry exactly, the splice fires exactly when referenced, the pruner drops what a
program does not call, the WASM refusal names the package) and no test opens a window.

## How the renderer is tested

No test may open a window (`.kb/objc.md`). **`scene:offscreen` closes that, and it is not a
second render path.** `metal:offscreen` builds a `metal:context` whose `target` slot holds a
shared-storage BGRA8 texture instead of a `CAMetalLayer`; `metal:frame` asks that slot once
per frame and takes the drawable's texture or the context's own, so ONE encoding path serves
both, and an offscreen frame is `waitUntilCompleted`'d instead of presented. `metal:pixels`
reads it back with `getBytes:bytesPerRow:fromRegion:mipmapLevel:` into an `objc:data` block
-- `width*height*4` bytes, BGRA, row 0 at the top, deliberately NOT converted to RGBA.
`scene:offscreen` and `scene:viewer` differ only in the context they hand
`scene::%viewer-over`.

`SceneOffscreenRenderTest` (macOS-gated, skipped without a Metal device) asserts: a red box
is red in the middle and background in the corners; a solid added FIRST is not overwritten by
one added behind it (the depth attachment); a facet wound counter-clockwise seen from outside
draws and the same facet reversed is culled; `scene:fit` leaves no solid pixel on the frame
border from four camera angles; the same scene renders byte-identical twice; and the arrow /
triad pixel shapes. Each frame is written to `target/scene-frames/*.png` and every assertion
names its file -- the PNG writer is `javax.imageio` in the TEST, not a rung of `metal`.

- **`scene::%render` sets `setFrontFacingWinding:` + `setCullMode:` explicitly.** It culled
  nothing before. geom winds counter-clockwise seen from outside and Metal decides facing in
  CLIP space (y up), not in the y-down framebuffer, so the front winding is
  `metal:+winding-counter-clockwise+` -- measured, not reasoned: the first cut said clockwise
  and drew every solid's FAR surface, which a centrally symmetric cube cannot tell apart from
  the near one. **The pinning shape is a single quad, not a box.**
- **`(scene:grid v :extent nil)` drops the grid**, as `(scene:axes v nil)` drops triads.
- **A `geom:volume` oracle cannot detect an inverted solid**: the divergence integral is
  `abs`'d, and a point reflection leaves each triangle's normal unchanged while moving it to
  the antipode. So the winding check is the renderer's, not the modeller's. (`geom:scale`/
  `nscale` by `-1` no longer produce that mesh, so an inverted solid can only be built by
  hand through `geom:polyhedron`.)

## Clicking: `scene:ray` and `scene:on-click`

- **`scene:ray v x y` is the primitive, and it answers a LINE**: `(origin direction)`, world
  space, from view coordinates in points (AppKit's -- origin bottom-left, `+y` up).
- **`scene:on-click v hook` is the convenience, and it answers a POINT** -- where that ray
  meets the plane through the ORBIT TARGET facing the camera, the one plane a viewer can pick
  without being told. `nil` removes the hook; it is called on the main thread.
- **A click is a press released without travelling more than 4 points**, measured by
  `scene::%moved` accumulated over the drag. The deadzone is in the RELEASE arm and
  deliberately not in the drag arm: the orbit those four points also performed is invisible,
  whereas a drag ignoring its first four points would start with a jump. A shift-drag (the
  pan) never produces a click. The hook is followed by one `scene:refresh`.

`scene:ray` needs no window, device or contents, so `SceneLibraryTest` drives it and
`scene::%click-point` over a bare `(make-instance 'scene:viewer-state :width ... :height ...
:target ...)`. Uncovered: the NSEvent that reaches them, as with `scene::%view-point`.

**An IK constraint on any consumer that composes rotations.** A chain posed by solving for a
WORLD-frame angular velocity has to carry it back into each joint's frame as
`Rp^T Rot(w) Rp . R`, and that sandwich DOUBLES the parent's orthogonality error into the
child every time it runs: eight solver iterations a frame down a five-joint chain is 2^8 a
frame, and the error went 1e-16 -> 1e-2 in five frames. Stating the Jacobian in each joint's
OWN frame -- block `M . R` with `R` the joint's world rotation, update `R . Rot(w)` --
removes the sandwich (drift ~1e-8 a frame) and is 1.5x faster. `geom:rotation-of` slots are
float32 by the package's own rule.

## The browser twin

`examples/browser/webgl-solids/` is the renderer `scene` cannot be: the same design over
WebGL2 (`.kb/wit.md`, a `--no-wasi` reactor). It consumes `geom:mesh` and
`geom:world-transform` UNCHANGED and contains no modeling code at all -- a second modelling
layer in the browser is how the two renderers would drift. Two differences: OpenGL's clip
space puts z in [-1, 1] where Metal's puts it in [0, 1] (one row of the projection), and
WebGL renames a buffer behind your back where Metal makes a rewritten buffer rotate copies.
Culling needs no statement: GL's default front winding is already counter-clockwise, geom's.

**The two renderers must orbit the same way, and one sign separates them.** Both drive the
identical two constants -- `(- azimuth (* 3.4 dx))` and a 2.6-scaled elevation term, clamped
to +-1.5 -- over a drag normalized by the viewer's height. **The trap:** a DOM client delta
puts +y DOWN and AppKit's `locationInWindow` puts +y UP, so the same code is two opposite
cameras. The elevation term is therefore negated in `scene::%orbit` and NOT in
`scene::%view-point` -- the pan arm reads the same delta and wants AppKit's sense, since a
target moved AGAINST the drag is what makes the model follow the cursor; flipping the point
would invert the pan along with the orbit. (`dx` needs no flip.) The browser twin has no pan.

Splitting the arithmetic out of `%on-mouse-dragged` into `scene::%orbit` / `scene::%pan` is
what makes this testable: `SceneOffscreenRenderTest` drives both functions directly.
Untested is the ONE line that reads the event, `scene::%view-point`: that `locationInWindow`
is y-up is a premise, not an assertion, so a flip there would invert both gestures with every
test still green.

## `IndentRules`

**No entry is needed.** Not one member takes a body, so `rontolisp format` laying a call out
as a function call is correct. A future member that DOES take a body (a `with-...` shape)
would need one (`formatter.md`).

## Tests

`eval/GeomLibraryTest` -- `aClosedProfileIsCappedAtNeitherEndSoATorusHasAHole`,
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

`eval/GeomKernelsTest`; `codegen/jvm/JvmGeomKernelCompilerTest`;
`eval/LibraryDefunPrunerTest#aDefclassDoesNotKeepTheDefunOfTheSameName`; `MetalLibraryTest`;
`SceneLibraryTest`; `SceneOffscreenRenderTest` --
`fitFramesASolidWhoseUnitsAreMetresRatherShrinkingItToADot`, `aTriadIsAddedAsOneArgument`,
`aMeshReadOutOfAModelFileDrawsLikeAnyOtherSolid`.

ci-spec: `geom-solids-cross-backend`, `geom-arrow-cross-backend`,
`geom-transforms-cross-backend`, `geom-csg-cross-backend`, `geom-scale-cross-backend`,
`geom-print-object-cross-backend`, `geom-read-model-cross-backend`,
`geom-read-ply-gltf-cross-backend`.
