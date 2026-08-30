# PLY and glTF/GLB readers, plugged into the seam `geom:read-model` already has

Difficulty: High

The glTF half is what makes this High; **the PLY half alone is Medium** and can
land on its own. Both plug into a seam that is already built, documented and
proved by two readers, so no design work is needed before starting -- read
`.kb/geom.md`, "Reading a model file", which is the spike's whole finding.

## What already landed (2026-08-30)

`geom:read-obj`, `geom:read-stl` (both dialects) and the dispatcher
`geom:read-model`, in `geom.lisp`, with the format sniffed from the file's own
bytes. `geom::%model-format` ALREADY recognizes `:ply`, `:gltf` and `:glb` and
refuses them by name -- "dragon.ply is PLY, which this build does not read yet"
-- so the work here is to replace two `geom:read-model` case arms with readers,
not to invent a mechanism.

**The seam, verbatim** (`.kb/geom.md` has the reasoning):

- a reader is `(path color label) -> a geom:solid`, or a LIST of them for a
  format carrying several meshes;
- it builds with `geom::%build-solid` -- a list of points, a list of index
  loops, a colour, a label -- which IS the one internal representation;
- numbers come out of text through `geom::%scan-number` and out of binary
  through `read-sequence` over a packed buffer;
- dispatch is a `case`, deliberately not a table, so `LibraryDefunPruner` can
  see which arm a program reaches;
- adding a format is four edits: one reader defun, one `geom::%model-format`
  clause, one `geom:read-model` case arm, one `PackageRegistry.GEOM_FUNCTIONS`
  entry.

## PLY (Medium, can land alone)

ASCII and `binary_little_endian` (and, honestly, `binary_big_endian` should be
refused by name rather than mis-read: the packed `read-sequence` path is
little-endian by contract, `.kb/binary-sequence-io.md`).

The header is text and small -- `element vertex N`, a `property` list per
element, `end_header` -- so `geom::%scan-number` and `geom::%first-token` cover
it. The body is the work:

- **Vertex properties are arbitrary and ordered.** `x y z` are usually the first
  three float32s but real files carry more: the Stanford `bun_zipper.ply` has
  `x y z confidence intensity` (all float32), and `trimesh`'s `cycloidal.ply`
  has `x y z` float32 plus `red green blue alpha` uchar -- a 16-byte stride that
  is NOT uniform, so the whole vertex block cannot be one `read-sequence`.
  A uniform-width all-float32 file CAN be: read `n*k` floats in one transfer and
  slice columns, which is the fast path worth having.
- **A face is a LIST property** (`property list uchar int vertex_indices`), so
  its stride is per-face: one 1-byte count then that many int32s. Two
  `read-sequence` calls a face, like the STL reader's two a triangle.
- **Per-vertex and per-face colours are dropped** -- `geom:solid` has one RGB.
  Say so in the doc page rather than averaging them into something.

Test corpus that was used during the spike (all permissively licensed, all
downloadable, none checked in): `graphics.stanford.edu/pub/3Dscanrep/bunny.tar.gz`
(`bun_zipper.ply`, ASCII, 35,947 v / 69,451 f, with the two extra float
properties) and `raw.githubusercontent.com/mikedh/trimesh/main/models/cycloidal.ply`
(binary_little_endian, 21,384 v / 43,368 f, with the uchar colours).

## glTF 2.0 / GLB (High)

The modern runtime standard and the one worth having, but it is a different
shape of problem from OBJ/STL/PLY: it is a SCENE, not a mesh.

- **GLB** is a 12-byte header, a JSON chunk and a BIN chunk. The JSON goes
  through `rontolisp:json-parse` (`json.lisp` is Lisp source, so a big JSON
  costs interpreter time -- but a glTF's JSON is kilobytes, not megabytes).
  `.gltf` is the same JSON with external `.bin` files and/or base64 `data:`
  URIs; base64 decoding of a multi-megabyte buffer in Lisp is the cost to
  measure BEFORE promising it.
- **The buffer layout is exactly what `read-sequence` over a packed buffer was
  built for**: an accessor names a bufferView with an offset, a component type
  (5126 = float32, 5123 = uint16, 5125 = uint32) and a count, tightly packed
  when `byteStride` is absent. POSITION -> a float32 array, indices -> a
  uint16/uint32 vector: one transfer each. A NON-ABSENT `byteStride` is the case
  that needs a per-element read, like PLY's colours.
- **A node hierarchy maps onto `geom:node`/`geom:attach`, and that is the seam's
  list-answering arm.** One glTF primitive -> one `geom:solid` (with the
  material's `baseColorFactor` as its colour); one glTF node -> one `geom:node`
  posed by its TRS or its 4x4 matrix; the reader answers the FLAT LIST of solids
  attached under a shared root, so `scene:add` splices it and each solid's
  `world-transform` carries its parents. Nothing in `scene` has to change.
- **What must be refused by name rather than half-read**: `mode` other than 4
  (triangles), sparse accessors, Draco/meshopt extensions, skins and
  animations. A silent partial read of a glTF is worse than a refusal.
- **A non-rigid node matrix is the one real type-model collision.** glTF nodes
  carry SCALE, and `geom:transform` is rigid by an explicit decision
  (`.kb/geom.md`, "Scaling"). The honest resolution is to bake a node's scale
  into the primitive's VERTICES with `geom:nscale` at read time -- geometry, not
  pose, which is exactly what that section says scaling is -- and to keep only
  the rotation and translation on the node.

Corpus: `raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/`
-- `Box/glTF-Binary/Box.glb` (1,664 B, the smallest possible check) and
`Duck/glTF-Binary/Duck.glb` (120,484 B, one textured mesh under one node).

## Also not done, and cheap next to the above

- **Writing** any format. `read-`/`write-` is the pair the naming leaves room
  for; `examples/macos/scene-model-file.lisp` hand-rolls an OBJ writer and a
  binary STL writer in about a dozen lines each, which is roughly what
  `geom:write-obj` / `geom:write-stl` would be.
- **Vertex welding.** An STL solid carries three vertices per facet because the
  format has no index table. A `geom:weld` would serve any mesh, not just a
  read one, and is a modelling operation rather than a reader's business.
- **A byte-vector or stream entry point**, which is the only thing that could
  make a reader usable in the browser playground (it can `fetch` bytes but has
  no filesystem). The blocker is not API design: `read-sequence`'s packed fast
  path DECLINES every in-memory stream, so a binary format read out of a byte
  vector would have to decode IEEE-754 in Lisp -- at the measured ~300 ns an
  `aref` on the interpreter, hopeless. The missing primitive is an in-memory
  byte stream the packed path accepts (`.kb/binary-sequence-io.md`'s
  "Re-evaluation trigger" is the same shape of question).

## Measurements from the spike, so this does not have to be redone

Apple M4 Max, 2026-08-30. Full table in `.kb/geom.md`, "Reading a model file".

- Text parsing is `char-code` scanning and there is no faster spelling: per
  float on the interpreter, `read-from-string` is 0.68 us, a whole
  parenthesised line through the reader 1.85 us, a hand-rolled scan 20 us --
  and the reader is UNUSABLE (it answers the symbol `|1.30E-2|` for exponent
  notation on both WASM backends, chokes on `#` and `|`, reads `739/1` as a
  ratio, and drags the runtime reader into compiled output, which `json.lisp`
  already declines it for). `parse-integer` is 5.2 us a call, no help.
- A 100k-triangle 4.6 MB OBJ: 8.9 s on the interpreter, **81 ms** compiled to a
  `.class`. A 2.4 MB one is 2.7 s on wasm preview 1.
- Binary reading through `read-sequence` costs nothing per number: 100k STL
  triangles in 498 ms on the interpreter.
- `geom:mesh` costs about as much as parsing (~52 us a triangle interpreted),
  and is the dominant load-time cost of a big mesh. Not the reader's fault and
  not the reader's to fix.
- Rendering is unaffected: a 69,451-triangle bunny redraws in **7 ms**.
