# The `geom` verbs: `move`/`turn` fight the package's own nouns, and `scale` mutates where every other geometry op builds

Difficulty: High

Two naming defects, one of which is a design defect wearing a name. Fix both in one
change: they interact, and fixing only the first makes the second worse.

**No compatibility aliases anywhere in this item.** The package landed days ago and has
no callers outside this repository. Old spellings are removed, not deprecated.

## 1. `geom:move` -> `geom:translate`, `geom:turn` -> `geom:rotate`

Every noun in the package is already the mathematical one -- `geom:make-transform
:translation :rotation`, `geom:translation-of`, `geom:rotation-of`,
`geom:world-translation`, `geom:world-rotation`, `geom:axis-angle-matrix` -- and only the
two verbs that change them use a second, informal vocabulary. The standard verbs in 3-D
graphics and robotics are `translate` and `rotate`, and both are free in the package.

The accumulate/set split does not move: `translate` and `rotate` ACCUMULATE onto the
local transform (the sense those verbs carry in every graphics API), `place` and
`reorient` SET it. Do NOT rename those two -- with `rotate` taking the accumulating
sense, `reorient` reads as its absolute counterpart, which is exactly right.

## 2. `scale` is the real problem, and the rename exposes it

After part 1 the package reads `translate` / `rotate` / `scale` like the classic
transform trio. It is not one, in TWO independent ways:

- **Different subject.** `translate` / `rotate` change a `node`'s pose and leave the
  geometry untouched. `scale` REWRITES a `solid`'s model vertices and drops the mesh
  and wire caches.
- **Different discipline.** Every other geometry operation in the package BUILDS --
  `union`, `difference`, `intersection`, `section` all return a fresh solid with its
  own `history`. `geom:scale` is the single in-place vertex mutation in the whole
  library, and it is spelled with a plain verb that gives no warning.

Resolve this properly. The recommended answer, unless measurement or the code says
otherwise:

- **Keep the transform RIGID.** Do not add a scale slot to `geom:transform` or to
  `geom:node`. A uniform scale would close mathematically (a similarity is a group), but
  `geom:volume`, `geom:centroid` and `geom:surface-area` are computed from the MODEL-space
  mesh and know nothing about the node's transform, so a scaled node would silently
  report its unscaled volume; the CSG path (`%world-polygons`), `%solid-bounds`, the
  per-draw uniform in `metal.lisp` / `scene.lisp` and `invert` would each need the scale
  threaded through. This is a CAD-flavoured package -- a boundary representation with
  booleans and real measurements -- and in CAD scaling changes the PART. The scene graph
  is for placement.
- **Adopt CL's own functional/destructive convention, which is what this package was
  missing.** `reverse`/`nreverse`, `union`/`nunion`, `substitute`/`nsubstitute`:
  - `geom:scale` becomes FUNCTIONAL -- answers a NEW solid, like the booleans beside it.
    The copy carries vertices, facets, color, label and `history` (append the scale to
    it, as the booleans do); it is a fresh, UNATTACHED node, so parent, children and
    `user-data` are not carried. Say that in the docs: a solid already in a viewer wants
    the destructive one.
  - `geom:nscale` is the in-place version -- today's behaviour, caches dropped, answering
    the same solid.
  - The convention applies to GEOMETRY operations only. `attach` / `detach` / `place` /
    `translate` / `rotate` stay as they are: they are pose mutators on a node, not
    sequence-style transformations, and CL does not `n`-prefix those either.
- **Accept a non-uniform factor while you are here** -- a number or a 3-vector. The mesh
  is rebuilt from the facets with a fresh Newell normal per triangle, so non-uniform
  scaling of a BREP costs nothing extra once the cache is dropped; refusing it would be
  an arbitrary limit. Check that `%facet-normal` and the winding survive it (a negative
  factor MIRRORS and inverts the winding -- either refuse a negative factor naming it, or
  flip the facets; decide, and pin whichever with a test).

If measurement or the type model says a different resolution is better -- including
putting a uniform scale in the transform after all -- **that finding is the deliverable**:
implement it and write the reasoning into `.kb/geom.md`. Either way the "why not a scale
slot" argument above must end up in `.kb/geom.md` so the question is settled in writing
and not re-litigated.

## Sites

- `src/main/resources/am/ik/rontolisp/eval/geom.lisp` -- the two defuns, `geom:scale`,
  and the comments above both groups (they list the mutators by name).
- `PackageRegistry.GEOM_FUNCTIONS` -- `"MOVE"`, `"TURN"` -> `"TRANSLATE"`, `"ROTATE"`,
  plus `"NSCALE"`. Miss this and the symbol is misclassified as a user symbol.
  `.kb/geom.md`'s "57 exported functions" count moves with it.
- `src/test/resources/ci-spec.yaml` -- 7 `move`/`turn` occurrences plus the `scale`
  cases; add a case pinning functional-vs-destructive scale and a non-uniform factor
  across all four backends.
- `src/test/java/am/ik/rontolisp/eval/GeomLibraryTest.java` (22 occurrences),
  `SceneOffscreenRenderTest.java` (4). Rename the test METHODS whose names carry the old
  verbs. Per CLAUDE.md, the new behaviour gets a FAILING test first.
- `examples/browser/webgl-solids/solids.lisp` (7) -- then `ExamplesE2eTest`
  (`-Drontolisp.examples.only=webgl-solids`).
- Docs, en and ja in the same commit, byte-identical fences:
  `reference/functions/geom-move.md` -> `geom-translate.md`, `geom-turn.md` ->
  `geom-rotate.md` (`git mv`, H1 and signature with them), a NEW `geom-nscale.md`, the
  rewritten `geom-scale.md`, the `_catalog.yaml` entries, the rows in
  `reference/functions/geom.md`, and the prose in `guides/solid-modeling.md`,
  `geom-place.md`, `geom-local-transform.md`, `geom-bounds-center.md`, `geom-union.md`,
  `geom-difference.md`, `geom-intersection.md`, `scene-animate.md`.
- `.kb/geom.md` -- the mutator list under "The type model, and why", the `:frame` bullet,
  the export count, and a new paragraph stating the pose/geometry split and the
  functional/destructive convention as the package's rule for future additions.

`grep -rn 'geom:move\|geom:turn\|GEOM:MOVE\|GEOM:TURN'` over the tree must come back
empty at the end.

## Verification

- `./mvnw spring-javaformat:apply test` (full suite).
- `./mvnw -Pnative clean package -DskipTests` then `CiSpecE2eTest` with
  `-Drontolisp.binary` -- required, `ci-spec.yaml` changed.
- `./mvnw -Dtest=ExamplesE2eTest -Drontolisp.examples=true -Drontolisp.examples.only=webgl-solids test`.
- `./mvnw -Dtest=DocExamplesTest test` and `./mvnw -f docs-tool/pom.xml test` (doc files
  are added, removed and renamed, so both the catalog and the layout move).
- `./mvnw -Pweb compile`, after the suite.
- Format the Lisp sources per CLAUDE.md's "After Task Completion".
