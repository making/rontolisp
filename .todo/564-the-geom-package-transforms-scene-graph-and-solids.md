# The `geom` package: rigid transforms, a scene graph, and solids

Difficulty: Medium

Member 1 of `563-solid-modeling-and-a-3d-viewer.md`; read the parent and
`563-solid-modeling-and-a-3d-viewer/README.md` first. `geom.lisp` in that directory is a
working prototype of everything below, verified on all four backends -- it is the starting
point, not the deliverable: it has no tests, no docs, no pruning story and no `.kb` file.

## What ships

A Lisp-source library on the `linalg.lisp` / `appkit.lisp` pattern (`.kb/linalg.md`,
`.kb/objc.md`): `src/main/resources/am/ik/rontolisp/eval/geom.lisp`, a `GeomLibrary` beside
`LinalgLibrary` (parsed once and cached; the interpreter evals `forms()` the first time a
`geom:`-qualified function resolves; `CompileFrontend` prepends them when the program
references the package), and a `PackageRegistry` entry. NO Java implementation: every
number here is `linalg`'s, and the package must stay reachable from the browser playground
and both WASM backends, so nothing may reach for `objc`, `java:` or the filesystem.

Everything is float32 (`:element-type 'single-float`), because a packed single-float array
IS a GPU vertex buffer's bytes and `objc:data` takes one of any rank -- so a mesh reaches
Metal with no conversion (`.kb/objc.md`, "Metal"). Every `linalg` transform preserves the
width.

### The three types

- **`transform`** -- a rigid motion: a 3-vector `translation` and a 3x3 `rotation`. A
  VALUE: no parent, no identity, no cache, and `compose` / `invert` / `transform-point` /
  `inverse-transform-point` build new ones. Constructors take `:translation` plus one of
  `:rotation` / `:rpy` / `:axis`+`:angle`.
- **`node`** -- a scene-graph node that HAS a local transform (composition, not
  inheritance: a solid, a camera target and a bare joint frame are all nodes with no slot
  any of them does not use). `attach` / `detach`, a memoized `world-transform` invalidated
  down the subtree on every pose change, and the mutators `move` / `turn` / `place` /
  `reorient`. **`:frame` is a keyword, not a positional flag** -- `(geom:move n v :frame
  :parent)` needs no manual, and `:local` is the default.
- **`solid`** -- a node carrying a boundary representation: `vertices`, a rank-2 (n 3)
  packed array of MODEL coordinates, and `facets`, a list of index loops each
  counter-clockwise seen from outside. One vertex array makes a whole-solid transform one
  matmul; model coordinates are what lets the mesh be cached (below).

### The constructors

`box`, `cylinder`, `cone`, `sphere`, `torus`, `extrusion` (a closed profile swept along a
vector -- the general prism), `revolution` (a profile turned about z, with end caps added
only where the profile does not reach the axis), and `polyhedron` (raw points and loops,
the escape hatch). Noun constructors taking keywords; the one measurement that names the
shape may be positional (`(box '(100 200 300))`), nothing else is.

### The mesh, and why it is cached ON the solid

`mesh` answers a packed single-float array in MODEL space, 18 floats a triangle (three
corners of position + normal), fan-triangulated per facet with a Newell normal, computed
once and kept in a slot. `wireframe` answers each edge once, 6 floats a segment, likewise
cached. `scale` and any future vertex mutation must invalidate both.

This is the parent's load-bearing measurement (README result 3): a rigid solid's triangles
never change, only its pose does, so a renderer that re-tessellates per frame spends 380 ms
a frame on a 60-solid model where one that caches spends 9.0. `mesh` must therefore be part
of the PUBLIC surface, not an internal detail of a renderer.

A `user-data` slot rides along for a consumer's own state -- the renderer keeps its GPU
buffers there. **Re-examined 2026-08-29, and the reason it exists has changed**: the
liveness half of the old reason is gone -- a hash of a node used to be exponential in the
graph reachable from it, and a work budget now bounds it (`.kb/hash-tables.md`), so an
`eq` table keyed by a node RETURNS. What has not changed is `.todo/012`: `:test 'eq` is
accepted and ignored, so the table compares its keys STRUCTURALLY, and two distinct nodes
with equal slots -- which sibling scene nodes routinely are -- collide into ONE entry.
That is a wrong answer, not a slow one, so `user-data` is still the correct design and
would be even after 012 lands (the cache lives with the thing it caches, and `detach`
cannot orphan it). The comment in the source must say THAT, not the cost story.

### Measurements

`bounds` (of a solid or a list of them, in world coordinates) with `bounds-center` /
`bounds-extent` / `bounds-union`; `volume` and `centroid` by the divergence theorem over
the mesh; `surface-area`. Volume doubles as a winding check -- a facet wound the wrong way
subtracts.

## Do

1. Port `geom.lisp` into `src/main/resources/...`, in the canonical shape the other shipped
   libraries use (external single-colon public names, `geom::` for internals, bare `cl`
   names, no package resolution needed). Check the portability constraints the sibling
   libraries honour and `geom.lisp` has not been audited against: a `do` loop always
   declares at least one variable, a parameter is never `setq`'d (bind a copy), and a
   literal `:element-type` reaches every `make-array` so the compiled backends pick the
   float[] repr statically.
2. `GeomLibrary` + `PackageRegistry` + the `CompileFrontend` splice, following
   `LinalgLibrary` exactly. Check `LibraryDefunPruner` behaviour: `geom` is large and a
   program that uses `box` alone must not carry `revolution`'s tessellator
   (`.kb/library-defun-pruning.md`).
3. Tests. The modeling half is ordinary Lisp and there is no excuse for it to be untested:
   `oracle.lisp` in the spike directory is the seed -- closed-form volumes and areas,
   `compose`/`invert` algebra, a joint chain's forward kinematics against hand-computed
   values, bounds under transforms, and the mesh cache's identity. Add the cross-backend
   pin (`ci-spec.yaml`), since this must behave identically on four backends and the
   trigonometry is where they would diverge.
4. Docs: `doc/{en,ja}/guides/` for the package as a whole, plus per-name reference pages
   and `_catalog.yaml` entries, mirrored byte-identically in the code fences
   (CLAUDE.md, "Documentation Site"). `DocExamplesTest` runs them, so every example must be
   runnable and headless -- no `scene` reference in a `geom` page.
5. `.kb/geom.md`: the type model and WHY (transform is a value; node has-a; model-space
   mesh), the float32 rule and its GPU reason, the winding convention, the cached-mesh
   invariant WITH the two numbers from the spike so a later regression is visible, and the
   cross-backend pin's name.
6. `IndentRules` needs no entry unless a member of this package takes a body -- none in
   this item does. Say so in the `.kb` file rather than leaving it an open question.

## Out of scope

Boolean operations (member 3), anything that draws (member 2), and mesh file formats. A
`solid` whose facets came from an STL is just `polyhedron`, and the reader for one is not
this item.
