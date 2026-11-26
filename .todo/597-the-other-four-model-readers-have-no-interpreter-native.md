# The other four model readers have no interpreter native

Difficulty: Medium

Split out of the work that put `eval/GeomKernels` over `geom:read-obj`,
`geom:mesh`, `geom:wireframe` and `geom::%vertex-extremes` (2026-08-31,
`.kb/geom.md`, "The interpreter's native kernels"). That seam took a 155 MB
scanned hand from minutes to 1.2 s on the interpreter, and it covers ONE format.

`geom:read-stl` (both dialects), `geom:read-ply` (both dialects) and
`geom:read-gltf` are still the `geom.lisp` defuns on every backend, so an
interpreter session that opens one still pays the interpreted cost that
`.kb/geom.md`'s "Measured" table records:

| file | interpreter today |
|---|---|
| bun_zipper.ply, ASCII, 3.0 MB (35,947 v / 69,451 f) | 7,314 ms |
| cycloidal.ply, binary, 1.08 MB (21,384 v / 43,368 f) | 1,163 ms |
| armadillo.stl, binary (100k triangles) | 498 ms |
| Duck as a base64-embedded .gltf (138 KB) | 985 ms |

The ASCII PLY is the one that hurts -- 7.3 s for a file a tenth the size of the
hand -- and it hurts for exactly the reason `read-obj` did: `geom::%scan-number`
per float, in the interpreter.

## How to do it

The seam is already there and each reader is one more `define(...)` in
`GeomKernels.install`. What each needs:

- **`read-ply` ASCII**: the same `Scanner` this item's sibling already carries,
  plus the header walk (element/property declarations). The binary dialect reads
  through `read-sequence` over a packed buffer and is already within 2x of the
  compile backends, so it may not be worth a native at all -- measure before
  writing one.
- **`read-stl`**: the ASCII dialect is a `Scanner` walk; the binary one is
  `read-sequence` again. Same measure-first rule.
- **`read-gltf`**: the cost is `rontolisp:json-parse` over the JSON chunk and the
  base64 decode of an embedded buffer, not geometry. That is a `json.lisp`
  question rather than a geom one, and a native for `rontolisp::%json-parse`
  would pay off far more widely than one for `read-gltf`.

## The rule the seam is held to

**Bit-identity, not tolerance.** A native answers what its defun answers, on
every input, or it declines (Java `null`) and the defun runs. Extend
`GeomKernelsTest`, which runs every fixture down both paths -- with the natives
and with `setGeomKernels(false)` -- and compares the printed arrays element for
element. `ci-spec.yaml`'s `geom-read-model-cross-backend` and
`geom-read-ply-gltf-cross-backend` are the cross-backend half.

## And the JVM half, which now exists (2026-08-31)

The compiled backend got the same four members on the same day
(`codegen/jvm/JvmGeomKernelCompiler` -> `JvmGeomTemplate`, `.kb/geom.md`, "The JVM
backend's kernels"), so a reader done here has a SECOND transcription to write --
the same kernel against the compiled value representation, added to that class's
`KERNELS` map and to `gateMembers()`. Do them in the same round: the two are
compared against each other by `ci-spec.yaml`, and a reader accelerated on one
backend only is the asymmetry this item was spun off from, one layer along.

Each reader also has a `%build-solid` shape to hand its result to. `read-obj`
uses `geom::%solid-of-vertices` (the packed-array half of `%build-solid`, split
out for it); a reader that already has its floats one at a time should do the
same rather than building a list of three-element lists per vertex.
