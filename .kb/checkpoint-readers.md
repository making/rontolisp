# Checkpoint readers: the `checkpoint` staging package and `safetensors:read`

**Invariant: a published checkpoint's tensors land in PACKED float arrays (a GGUF's Q8_0
tensor in a `rontolisp:quantized-matrix`, `.kb/quantized-matrix.md`), staged in bounded
chunks, through a file walked front to back — same arrays on every backend, with and
without `--simd`.**

## The three destinations, and what each source costs to reach one
`:element-type` is `'single-float` (default), `'double-float`, or **`'bfloat16` on the
interpreter and the JVM only** (every other backend refuses the width by name,
`.kb/bfloat16.md`). What the conversion costs is not uniform, and the differences are the
whole reason the width exists on the load path:

| source | -> `single-float` | -> `bfloat16` |
| --- | --- | --- |
| F32 | its own bytes, one `read-sequence` | narrowed AS IT STREAMS, one chunked pass |
| BF16 | staged u16 + `widen-float-bits` | **its own bytes, one `read-sequence`, NO widen at all** |
| F16 | staged u16 + `widen-float-bits` | staged u16, widened into an f32 scratch, narrowed in |

- **The BF16 -> `bfloat16` cell is the point.** A 2.2 GB bf16 checkpoint moves once, with no
  conversion and no staging buffer: `checkpoint:stage-float-bits` tests the pair and calls
  `read-sequence` straight into the destination (`.kb/bfloat16.md`, the packed-array section,
  and the bulk transfer itself is `PackedBuffer` / `_readSeqPacked`'s `short[]` arm). Every
  other cell goes through `checkpoint::%stage-widened` or
  `checkpoint::%stage-float32-narrowed`.
- **Never materialize the whole f32 tensor to narrow it.** At a billion parameters that
  transient is 4.4 GB, which is what the width was adopted to avoid; both narrowing paths are
  chunk-at-a-time, and `safetensors::%read-tensor` / `gguf::%read-tensor` hand a `bfloat16`
  destination straight to `stage-float32` rather than through an f32 tensor.
- **Only a BF16 SOURCE round-trips.** `.todo/675`'s frozen interface said a `'bfloat16` read
  would be EQUAL to the `'single-float` one "because widening is exact" — true for a BF16
  source and false for the other two: bfloat16 has eight mantissa bits, so an F16 value
  outside them changes. The f16 maximum 65504 rounds to the pattern `0x4780`, whose value is
  65536 and which `FloatText.bfloat16Text` PRINTS as `65500.0` (the shortest decimal that
  reads back as the same bfloat16, `.kb/bfloat16.md`). The tests assert the narrowed value,
  not equality.

## Libraries
- `checkpoint` (`eval/checkpoint.lisp`, `eval.CheckpointLibrary`): `make-tensor`,
  `stage-float-bits`, `stage-float32`, `skip-bytes` (plus the two internal narrowing
  paths `%stage-widened` / `%stage-float32-narrowed`). `safetensors` (`safetensors.lisp`,
  `eval.SafetensorsLibrary`): `read`, `header`, `entries`. Lisp-source libraries in the
  `geom` shape (`.kb/geom.md`), prunable by `LibraryDefunPruner`, in
  `PackageRegistry.CHECKPOINT_FUNCTIONS` / `SAFETENSORS_FUNCTIONS` and
  `resource-config.json`.
- **Splice order in `CompileFrontend`: `SafetensorsLibrary.process`, then
  `CheckpointLibrary.process`, both BEFORE `JsonLibrary` and the prelude.**

## Traps
- A reader WALKS front to back in tensor-offset order, passing unwanted tensors
  with `checkpoint:skip-bytes` -- which seeks past them when the stream tells its
  position (`file-position` seeks binary file streams on every backend) and walks
  them in bounded reads through a 64 KB scratch otherwise; a sharded checkpoint
  walks each needed shard once.
- A packed `(unsigned-byte 16)` vector costs 8 bytes/element on interpreter and JVM
  (`.kb/binary-sequence-io.md`), so `checkpoint:stage-float-bits` takes the STREAM and
  widens 1M-element chunks with `widen-float-bits ... :start`. The last chunk needs a
  buffer of its own size — `widen-float-bits` widens the whole vector handed to it.
- `make-array :element-type` does not signal on an unknown element type; it answers a
  boxed general array. That is DECIDED, not an oversight — SBCL answers the same and a
  `deftype` may be registered after the reference, so there is nothing to signal against
  (`.kb/array-literals.md`, "An UNKNOWN `:element-type` upgrades to `t`"). The
  postcondition is therefore the caller's: `checkpoint:make-tensor` is the ONE allocation
  path and asserts `(array-element-type a)`.

## safetensors
`u64` LE header length; that many bytes of JSON (`"<name>": {"dtype", "shape",
"data_offsets": [begin, end]}` plus `__metadata__`); tensor bytes at those offsets from
the end of the header, row-major.
- `header` -> parsed JSON (string-keyed hash table, `.kb/json.md`) + data start;
  `entries` -> `(name dtype shape begin end)` sorted by `begin`.
- `read`: rank-1 for a 1-D shape, rank-N otherwise (caller squeezes); F32 by one
  `read-sequence`, F16/BF16 through `stage-float-bits`, else an error naming tensor and
  dtype; `:only` is a name predicate; a directory argument probes
  `model.safetensors.index.json` then `model.safetensors`.
- Per-family name mapping is the consumer's (`examples/llm/llm.lisp`
  `load-hf-checkpoint`); the reader is per FORMAT.

## Tests
`examples/llm/safetensors-check.lisp` over `safetensors-check.safetensors`
(`safetensors-fixture.py`), expected `examples/.expected/safetensors-check.txt`, entry in
`examples.yaml` on all four backends, with and without `--simd` — but the
`wasm-component` token is a COMPILE-only leg there, and that output actually TRAPS on the
sharded section when it is run (`.todo/693`), so the RUN legs are interpreter, JVM and
WASM preview 1.

The `bfloat16` destination is pinned per engine, not cross-backend (two backends refuse the
width): `SafetensorsLibraryTest#readsIntoABfloat16DestinationAndNarrowsOnlyWhereTheWidthRequiresIt`
and `GgufLibraryTest#everyTensorTypeReadsIntoABfloat16Destination` cover all three source
types into it, and the bulk transfer under them is
`LispEvaluatorTest#readWriteSequenceMovesABfloat16ArrayAsItsStoredPatterns` /
`JvmLispCompilerTest#compileAndRunReadWriteSequenceMovesABfloat16ArrayAsItsStoredPatterns`,
both keyed on bf16 `0x3F80` = 1.0 so a byte-order mistake is obvious rather than plausible.

**Those are all one engine each, and the READER composed of them is where the two can
disagree**, so `cli/SafetensorsBfloat16CompilePathTest` runs one program through
`CompileFrontend` twice — interpreted, and as a compiled `.class` under `java -cp` — over a
fixture carrying all three source dtypes, and compares the printed BIT PATTERNS element for
element. It prints patterns rather than floats (no formatting accident can make two engines
agree), carries the two round-to-nearest-even ties `1.00390625` / `1.01171875` (16256 stays,
16258 lifts) so a truncating narrow shows, and reads twice with `checkpoint::%chunk` forced
to 5 so the chunked arms of both narrowing paths run at non-zero offsets. There is no WASM
leg by construction: those backends refuse the width by name.
