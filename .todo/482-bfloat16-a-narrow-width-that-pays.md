# 482. `bfloat16`: a narrow packed array that is both smaller and faster

Difficulty: High (the umbrella item; the children are sized individually)

Spiked 2026-08-22. Probes and their full numbers:
`.todo/482-bfloat16-a-narrow-width-that-pays/` (`README.md` there is the measurement
record).

**Re-verified 2026-09-03** (`README.md` there, "Round 2"): the width stands, three
premises moved, and the wider plan is now `.todo/670`. What moved: (1) the f16 decode
verdict below was a **Graal** verdict -- C2 auto-vectorizes the scalar `float16ToFloat`
loop to 14.9 Gelem/s -- but a fused f16 GEMV still loses on both JITs (0.30-0.58x), so
f16 stays out as a width and enters as a load-time conversion (`.todo/671`); (2) the
spike's own fused kernel falls to **0.20x under C2** from an inlining cliff, which is now
a design rule in `.todo/488`; (3) the memory premise in the next paragraph is wrong for
this box and for any 8 GB machine -- the width is about bandwidth, not fitting, until a
model is several times larger than the goal. Q8_0 measured 2.0x and is filed as a
weight-matrix type, not a float width (`.todo/672`); Q4_0 measured 1.1x on the CPU and
is not filed for it.

**The goal this item exists for: run a 1B-class model on rontolisp** (item 489).
`examples/llama2` today runs `stories15M` -- 60.8 MB of weights. A 1.1B-parameter model is
2.2 GB at 2 bytes a weight and 4.4 GB at 4. Both fit this box (121 GB) and a laptop; what
the halving buys at this size is **tokens per second** -- decode streams every weight
once per token, so the width is the bandwidth (1.6-2.0x measured, one thread and twenty)
-- and, on the device, whether the model fits under the residency cap (`.todo/490`).
Fitting in memory only becomes the constraint several times above 1B. Everything below is
in service of the tok/s.

`LispFloatArray` is already a sealed umbrella over two widths (`LispDoubleFloatArray` /
`double[]`, `LispSingleFloatArray` / `float[]`). This item adds a third, backed by a
`short[]`: **half the memory of `#f`, a quarter of `#d`, and 1.6x the GEMV throughput of
`#f` on a matrix too large for cache.**

> **Confirmed on the shipped kernels, 2026-09-05** (`.todo/488` closed): 1.49x under
> Graal and 2.00x under C2 at 4096x4096, one thread, on a cleared GB10 at base commit
> `2275c000`. The tables in THIS item are its spike probes (`Worth.java`, four
> accumulators + FMA) and stand as such; the shipped kernels differ from that shape only
> by the second rounding, which wasm's lack of a deterministic FMA makes permanent. The
> full record, the cache-resident cost and the no-size-gate decision are in
> `.todo/488-the-fused-bfloat16-gemv-kernels/README.md`.

Scope: **interpreter and JVM only.** The wasm backends, `--no-gc` and the component path
do not get the width and must refuse it loudly (item 486).

## Why bfloat16 and not IEEE binary16

The item was first written for IEEE f16, because `Float.floatToFloat16` /
`Float.float16ToFloat` are right there in the JDK. Measured, that is the wrong choice, and
the spike is the evidence. GEMV, 4 accumulators + FMA, narrow weights against f32 weights:

| shape | f16 vs f32 | **bf16 vs f32** |
| --- | --- | --- |
| 1024x1024 (4 MB, fits in cache) | 0.32x | 0.88x |
| 4096x4096 (67 MB, DRAM-bound) | 0.60x | **1.60x** |

The whole difference is the widening decode. bf16 **is** the top 16 bits of an f32, so
decoding is `bits << 16` -- one instruction, vectorizes trivially, and the halved bytes
then show up as the bandwidth win they were supposed to be. IEEE f16 needs a ~6-op
bit-trick per lane (JDK 25's Vector API has no half-precision element type), and that ALU
work costs more than the bandwidth it saves at every size and thread count tried. f16 is
pinned at a flat ~5.1 Gelem/s no matter how large the matrix gets; it never reaches the
memory wall it was added to relieve. (Under C2 the *scalar* f16 decode loop does
vectorize -- see the round-2 record -- but a GEMV is a float reduction, which no JIT
reorders, so the fused f16 kernel is 0.30x there and 0.58x under Graal. Same conclusion,
one more reason.)

Three more reasons the choice is not close:

- **Every modern LLM checkpoint is bf16** -- Llama, Mistral, Qwen, Gemma all publish bf16
  safetensors. Reading one needs no conversion at all. f16 is a GPU-inference format we
  would have to convert *into*.
- **bf16 -> f32 is exact and lossless**, being a pure shift. That makes a fused bf16
  kernel bit-identical to "widen to f32, run the f32 kernel", which removes the entire
  bit-identity question that `.kb/vec.md`'s lane-count pin exists to police. f16 has no
  such property.
- **bf16 has f32's exponent range**, so it does not underflow where f32 does not. The f16
  probe actually lost N(0, 0.02) weight samples to its 6e-8 subnormal floor (a relative
  error of 1.0 on those). bf16's cost is precision instead: 8 mantissa bits, a 3.9e-3
  relative round-trip error against f16's 4.9e-4. For weights that is the right trade and
  it is the trade the model publishers already made.

So `Float.floatToFloat16` does not get used, and `short-float` stays unclaimed for a
possible future IEEE f16 width. Neither f16 nor bf16 meets CL's *recommended* minimum of
13 mantissa bits for `short-float` (f16 has 11, bf16 has 8) -- but that is a
recommendation, not a conformance requirement, and it is not what decides this.

## What that changes about the design

An earlier draft of this item specified "storage width, no narrow kernels" because f16
compute always lost. **With bf16 that is reversed: the fused kernel is the point** (item
488), and it is safe to write because the widening is exact.

The one shape that stays true regardless: reads widen to a scalar `double` and writes
narrow, exactly as `single-float` already does. There is no bf16 *scalar* -- the width
lives entirely in the array storage.

Where the fused kernel is not worth it, the fallback is to widen once into an f32 scratch
and run the existing f32 kernel. `Worth.java` measures where that crossover sits by reuse
factor (how many vectors one matrix is multiplied by): at 4096x4096, widen-once reaches
parity with f32 at a reuse of about 16 and is a heavy loss at a reuse of 1. A decode-only
GEMV is reuse 1, which is why the fused kernel matters and the scratch route does not
carry this item.

## Children

| item | what it does | difficulty |
| --- | --- | --- |
| `483` | make the two-width assumption exhaustive -- prerequisite, lands first, no new type | Medium |
| `484` | `LispBFloat16Array`, `#bf16(...)`, `:element-type 'bfloat16` on the interpreter | Medium |
| `485` | the same width on the JVM backend; the embedded header does not fit in a `short` | High |
| `486` | the backends that do not carry it must refuse it, and `--gpu`/BLAS must decline it | Low |
| `487` | conversion and bulk width change: the bits pair, `coerce`, reading a bf16 file | Medium |
| `488` | the fused bf16 GEMV / dot kernels -- where the 1.6x comes from | DONE 2026-09-05 |
| `489` | the goal: a 1B-class model on rontolisp | High |
| `490` | bf16 on the device: `gemv_bf16`, the residency cap, the precision row | High |

Order: 483 first (a pure refactor that makes every later site a compile error rather than a
silent misroute), then 484, then 485 and 486 in either order, then 487, then 488, then 489.
`490` is a follow-on, not a fork: see below.

Related, filed 2026-09-03 under `.todo/670` and landable ahead of this chain: `671`
(f16 and bf16 *bits* widened in bulk into `#f`, on every backend -- what lets a bf16
checkpoint load before this width exists), `673` / `675` (GGUF and safetensors readers),
`674` (the byte-level BPE tokenizer), `672` (the Q8_0 weight matrix).

## Why this is not a CUDA item, even though the goal is an LLM

Items 483-487 are host-side and required whichever processor ends up doing the arithmetic
-- `--gpu` intercepts `vec:matvec` at the call level over a *host* packed array, so the
device cannot hold what the language cannot represent. Only 488 and 490 are a choice, and
on this machine it is a smaller choice than it looks:

**GB10's 128 GB of LPDDR5X is shared by the CPU and the GPU at ~273 GB/s**, and the CPU
path already reached 93 GB/s of it at 20 threads (`Par.java`). A bandwidth-bound GEMV on
the device is therefore worth roughly 2-3x the CPU `--parallel` path here, not the order of
magnitude a discrete HBM card would give -- which is the same thing `.kb/gpu.md` found from
the other side ("on unified memory an upload is a memcpy of the very bytes the CPU kernel
would have streamed").

bf16's own ~2x is **orthogonal** to that: it applies on both sides for the same reason.
So the CPU kernel (488) is not a detour to be skipped on the way to the device -- it is
the cheaper half of the win, it needs no device to develop or test, and because bf16 -> f32
is exact it is the **bit-exact oracle** a device kernel can be checked against. `.kb/gpu.md`
already carries a precision contract per member because the device diverges from the CPU at
f32; bf16 gets a better story than that, but only if the exact CPU answer exists first.

## The name

- **Type name: `bfloat16`.** It is not a CL type, and it should not pretend to be one:
  `short-float` means an IEEE-ish narrow float in every other Lisp, and bf16's 8-bit
  mantissa is not that. `bfloat16` is what the rest of the world calls it (C++23
  `std::bfloat16_t`, PyTorch `torch.bfloat16`, JAX, `ml_dtypes`), it is unambiguous, and it
  leaves `short-float` free if IEEE f16 is ever wanted alongside. It enters the type
  lattice as a documented rontolisp extension: a fourth `float` subtype, disjoint from the
  three CL ones.
- **Java identifiers: `BFloat16`.** `LispBFloat16Array`, `LispNames.BFLOAT16`,
  `FloatText.bfloat16Text`. One vocabulary, matching `LispSingleFloatArray` <-
  `single-float`.
- **Reader/printer prefix: `#bf16(`.** A single letter was the first instinct (`#b(`,
  beside `#f(` and `#d(`) and it is the wrong shape for the long term: `#b` already reads
  as the binary radix to anyone scanning the source, and the single-letter space runs out
  immediately once a second narrow width shows up. It will: IEEE `#f16` is the obvious
  neighbour, and quantized inference is heading for fp8 (`e4m3` / `e5m2`) after that.

  So the prefix is the **width tag**, and it establishes a scheme with room in it:
  `#bf16(`, and later `#f16(`, `#fp8(` -- the same vocabulary numpy, PyTorch and C++23
  use. Optionally `#f32(` / `#f64(` can be added as aliases of the existing `#f(` / `#d(`
  for symmetry; the short forms stay, they are in every existing program and doc.

  Lexing is the established shape (`#S(`, `#P"`, `#f(`, `#d(`: a fixed prefix that must be
  followed by the opening delimiter, else fall through to symbol reading), with one
  ordering requirement: the `#bf16(` branch must be tried **before** the `#x`/`#o`/`#b`
  radix branch, since that branch would otherwise claim the `#b`. There is no ambiguity to
  resolve -- `f` is not a binary digit, so `#bf16` is never a radix literal -- only a
  branch order to get right, and a test to pin it.

Prose may say "bf16" for the storage format -- that is what the bits are called -- but it
is never a name in the code or in the language.
