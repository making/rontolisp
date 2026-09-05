# 672. A Q8_0 quantized weight matrix, and its integer-dot `vec:matvec`

Difficulty: High

Part of `.todo/670`. `.todo/484` and `.todo/485` (the `#bf16` array this dequantizes
into and checks against) and `.todo/671` (the f16 scales a GGUF carries) all closed
2026-09-03, so nothing here is waiting. `.todo/673` (closed 2026-09-03 with the GGUF
loader in `examples/llama2/llama2.lisp`) landed the GGUF reader, which declines a Q8_0
tensor by name when its body is asked for -- metadata, the tensor directory and the
F16 / BF16 / F32 tensors all read, so this item begins from a model whose shape and
vocabulary are already loadable, and replaces a decline with an implementation. The
replacement surface is two places: `gguf::%read-tensor`'s `(= type 8)` branch and one
`handler-case` in `ci-spec.yaml`.

Two things to know that are NOT waits. `rontolisp:widen-float-bits` still declines a
`#bf16` DESTINATION ("does not yet", `.todo/487` steps 3-5), so a dequantize into
`#bf16` goes through `(setf aref)` or its own kernel until 487 lands. And `.todo/691`
(three hand-written UTF-8 decoders, which disagree on malformed input) is worth reading
first, but it does NOT gate this item: 673 reads Qwen3.5's GGUF metadata and vocabulary
today, so the decoder is already adequate for this path. 691 bites `tokenizer.json`
(`.todo/674`'s side), not the GGUF.

Measured 2026-09-03 (`.todo/482-bfloat16-a-narrow-width-that-pays/Quant.java`, record in
the README's round 2, section 5): a GEMV over Q8_0 weights with the activation quantized
to int8 per block and an **integer** dot -- ggml's `Q8_0 x Q8_1` shape, runq.c's shape --
runs at **2.00x f32 on one thread (Graal), 1.91x on twenty**, 1.15-1.2x over bf16, in a
quarter of f32's bytes, at 7.6e-3 relative GEMV error (bf16: 1.7e-3, f32: 3e-7). The
other Q8 shape, dequantize-and-FMA, is 1.37x under Graal and **0.12x under C2** (the
`.todo/488` inlining cliff, with `convertShape(B2I, part)` in an 8-call method); the
integer-dot shape survives both JITs. Half the small-model GGUFs on Hugging Face are
Q8_0, which is the input this item makes loadable at its published size.

## Why a new type and not a fifth float width

A Q8_0 tensor has no element you can set: a value is `q[i] * scale[i / 32]`, and writing
one element means re-quantizing its block. It is what PyTorch and ggml both make it -- a
**quantized tensor**, dequantize-on-read, immutable -- not a dtype of an ordinary array.
Putting it under the sealed `LispFloatArray` umbrella would hand every element-wise
kernel and every `setf aref` an arm they can only refuse. So:

- `rontolisp:quantized-matrix`: rank 2 (the weights that matter are matrices; rank 1 is
  allowed and is a matrix of one row), a `format` (`q8-0` now; the type is named for the
  concept so `q4-k` can join on the device, `.todo/490`'s successor), the dimensions,
  and the blocks. Interpreter: `byte[] q` + `float[] scales` + `int[] dims`. JVM backend:
  a bare `byte[]` with an int header (4 bytes per header value: rank, dims, then the
  scales as f32 bytes, then the quants) -- a `byte[]` is disjoint from every shape the
  `instanceof` dispatch already tells apart (`.todo/485`, `.kb/packed-integer-vectors.md`
  for the `long[]`-with-header precedent), so the accessors stay allocation-free.
  **The `byte[]` is load-bearing, not a preference: the existing packed integer vector
  must NOT be reused here.** Read 2026-09-03: `LispIntVector` and its JVM counterpart
  store 8/16/32-bit elements in a `long[]`, because `instanceof long[]` is the free
  representation discriminator on a backend with no type tags and a per-width `byte[]` /
  `short[]` / `int[]` would collide with the `int[]` charboxes. That trade was made for
  ironclad's SHA-256 working buffers (todo 194 stage 2) -- small ones -- and it costs
  EIGHT bytes an element. Routing Q8_0's quants through it would store a one-byte value
  in eight and make this type twice the size of the f32 matrix it exists to shrink,
  deleting its whole reason to exist. The scales are one per 32 elements, so they do not
  care; the quants are the whole point.
- `aref` reads the dequantized value as a `double`; `(setf aref)` signals; `array-rank`,
  `array-dimensions`, `array-total-size`, `array-element-type` (answers the format
  symbol) work; `typep`: not an `array` of any float type, its own type.
- `(rontolisp:quantize a 'q8-0)` from a packed float array (ggml's absmax-over-32 rule,
  `round` to nearest), `(rontolisp:dequantize m element-type)` to a fresh `#f` / `#bf16`
  / `#d`, and the readers (`.todo/673`) build one straight from the file's bytes.
  **Sequentially, from the front.** `file-position` answers `nil` on every backend
  (`.todo/390`), so nothing here may seek: a Q8_0 tensor's blocks -- 32 int8 quants then
  one f16 scale, repeated -- are walked in file order, and a scale is picked out of the
  bytes already read rather than sought to. This costs nothing (a checkpoint is read
  whole anyway) but it rules out the shape a `file-position` API would invite, so do not
  write one and then discover the constraint.
- Printing: `#<quantized-matrix q8-0 (rows cols)>` -- there is no literal syntax and
  there should not be one; a quantized matrix comes from a file or from `quantize`.

## The kernel, and the identity contract it gets for free

`vec:matvec` / `vec:matvec-into` accept a quantized matrix and an `#f` / `#d` vector.
The `--simd` kernel (interpreter `VecSimdKernels`, JVM `JvmSimdVectorTemplate`, mirrored
as `.kb/vec.md` requires) quantizes `x` to int8 per block of 32 (absmax / 127, `round`
half away from zero -- pin the rounding, it is the one place a backend could differ),
then per row and block: `B2S` widen, short multiply, short add of the two halves (safe:
|2 x 127 x 127| < 32767, which is why the activation is clipped to +-127 and not -128),
`S2I` widen, int accumulate, and ONE float multiply-add per block, `isum * (sw * sx)`,
into an f32 accumulator. `--parallel` splits rows as for every GEMV.

**The scalar `vec.lisp` defun does the same arithmetic**, over `aref` of the quants and
the scales -- not a dequantized double dot. Integer accumulation is exact in any order,
so the defun and the lane kernel agree **bit for bit** on the integer sum by
construction, and the only floating-point steps are one product and one accumulate per
block, in the same order on both. That is a stronger identity than `#f` has (whose lane
reduction needed the `SPECIES_128` pin), and the cross-backend test should assert
equality, not a tolerance. What is NOT identical to anything is the quantized GEMV
against the *dequantized* one -- 7.6e-3 -- and the docs say so: the number a Q8_0 model
produces is the number llama.cpp produces from the same file, not the number the bf16
file produces.

## Backends

Interpreter and JVM. wasm-GC, `--no-gc`, `--component`: refuse at the first
`quantized-matrix` -- a `quantize` call, a reader's Q8_0 tensor -- with the type and the
backend named (`.todo/486`'s shape). `--gpu` and `--blas` decline the type (an exhaustive
switch after `.todo/483` makes that an explicit arm). `rontolisp:jvm-export`: not a
boundary type until someone needs one.

## Verify

- `quantize` then `dequantize` of a random `#f` matrix is within 1/254 of a block's
  absmax per element, and ggml's own reference (`quantize_row_q8_0_ref`, transcribed
  as a test oracle) produces the same bytes.
- Defun == lane kernel == `--parallel`, **bit for bit**, on random matrices at several
  shapes, interpreter and compiled `.class`, Graal and C2.
- The GEMV against the `#bf16` GEMV of the dequantized matrix: relative error below 1e-2
  on N(0, 0.02) weights, recorded.
- Re-measure `Quant.java`'s table on the implementation, both JITs; the one-thread
  4096x4096 ratio must be clearly above bf16's, or the int-dot did not vectorize.
- A `.class` with a Q8_0 GEMV over a matrix of > 32767 columns and > 32767 rows: the
  header scheme's regression, as in `.todo/485`.

## The fixture, and its identity

The Q8_0 checkpoint this item reads is Qwen3.5-0.8B published by **ggml-org**
(`general.architecture = qwen35`, offered in BF16 / Q8_0 / Q4_0; `.todo/677` records the
publisher but never wrote down the repo id string, and a guessed one is worse than none).
Identify the file by its bytes, not by where it was fetched from:

| file | bytes | sha256 |
| --- | --- | --- |
| `Qwen3.5-0.8B-Q8_0.gguf` | 833592096 | `37ae482d336108d23516fa35e8e0c4126688d81018b87178a18d752a1357814f` |
| `Qwen3.5-0.8B-BF16.gguf` | 1557662496 | `9a7bed4041b7975e0f71fa34670d1e9025213bc92905ac0db75d36c4fa3fa623` |

Taken on dorian 2026-09-05, where both files live in `/home/administrator/models/`. **GB10
has neither and must re-fetch** -- its only cached model is `unsloth/Qwen3.8-Flash-Next-GGUF`
(Q4_K_XL + a BF16 companion), which is not this.

Why the hashes are worth the two lines: this item's central check is a byte-equality
against what `llama.cpp` produces from the same file. If the two boxes' copies hash
alike, then any divergence found later is in the code, full stop. Without that, a
mismatch has two candidate causes -- the implementation or the file -- and
`.todo/670`'s rule 7 is the record of what it costs to be unable to tell them apart. A
re-fetch that lands different bytes is not automatically wrong; diff the GGUF metadata
headers before concluding anything about the repo path.
