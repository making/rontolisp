# The narrow-float-width spike, 2026-08-22

Throwaway probes kept for reproducibility, NOT project code: outside `src/`, not in the
reactor, not formatted by `spring-javaformat:apply`, and nothing builds or tests them.
They exist so the numbers in `../482-bfloat16-a-narrow-width-that-pays.md` can be
re-derived on other hardware -- above all the pair of numbers that chose the format:
**an IEEE f16 array is never faster to compute over on the JVM (0.60x at best), and a
bfloat16 array is 1.60x faster on the same matrix.** The item was written for f16 first,
because `Float.floatToFloat16` is in the JDK; the measurement is why it is not.

Every file is a single-class JDK source-launcher program. Run each with:

```bash
java --add-modules jdk.incubator.vector <File>.java
```

`Text.java` needs no incubator module.

## The machine these numbers came from

NVIDIA GB10 (Grace Blackwell), aarch64 Cortex-X925, 20 cores, Oracle GraalVM 25.0.4.
CPU features include `fphp` / `asimdhp` (half-precision FP in scalar and NEON) and
`sve2`, so this is a *favourable* host for f16, not a hostile one. The Vector API still
reports `SPECIES_PREFERRED = S_128_BIT` (4 float lanes) -- it does not use SVE here.

A different machine changes every number; what should survive is the SHAPE: the widening
decode is ALU work that scales with the core, while the bytes saved only matter once the
kernel is DRAM-bound, and on this class of core the decode wall arrives first.

## The files

| file | question it answers |
| --- | --- |
| `Round1.java` | first round: is `Float.float16ToFloat` auto-vectorized, and does a f16 GEMV beat f32? (no, and no) |
| `Dec.java` | how fast can the widening decode possibly go? five variants against an f32 copy ceiling, plus an exactness sweep over all 65536 patterns |
| `Gem.java` | GEMV with the fastest *exact* decode, single accumulator |
| `Acc.java` | the same with 4 accumulators + FMA (the todo-480 shape), so the comparison is not dependency-chain-bound -- **this is the decisive one** |
| `Par.java` | does the picture change at 20 threads, where bandwidth is shared? (no) |
| `Text.java` | can the printer round-trip every f16 bit pattern through a shortest-decimal search, and how many digits does it need? (yes; 5) |
| `F16.java` | does JEP 508's `jdk.incubator.vector.Float16` -- already in JDK 25 -- change the answer on this host? (no: 0.20 Gelem/s with an f16 accumulator, 2.9 through `floatValue()`) |
| `Worth.java` | **the one that chose the format**: bf16 against f16 against f32, and the reuse factor at which "widen once into an f32 scratch" reaches parity |

## The results, in one place

Decode throughput, 4 Mi elements (`Dec.java`):

| variant | Gelem/s | exact? |
| --- | --- | --- |
| scalar `Float.float16ToFloat` loop | 1.92 | yes (intrinsic) |
| Giesen bit-trick, vectorized | 3.53 | yes |
| magic-multiply (`* 0x1.0p112f`) | 7.90 | no -- inf/NaN wrong |
| magic-multiply + masked inf/NaN fixup | 5.5 | yes for finite; NaN payloads differ |
| bf16 `<< 16`, for reference | 11.93 | (different format) |
| f32 -> f32 copy, the ceiling for this shape | 7.51 | -- |

The scalar intrinsic is *not* auto-vectorized into `FCVTL` by this JDK: a hand-written
vector decode is 1.8x-4x faster than the loop the compiler produces.

GEMV, 4 accumulators + FMA, f16 weights against f32 weights (`Acc.java`):

| shape | f32 Gelem/s | f16 Gelem/s | f32 GB/s | f16 vs f32 |
| --- | --- | --- | --- | --- |
| 288x288 (llama2 stories15M) | 16.08 | 5.06 | 64.3 | **0.31x** |
| 1024x1024 | 16.09 | 5.27 | 64.4 | **0.33x** |
| 4096x4096 | 8.70 | 5.13 | 34.8 | **0.59x** |
| 8192x8192 | 7.66 | 5.15 | 30.6 | **0.67x** |

f16 is pinned at ~5.1 Gelem/s at every size -- it is decode-ALU-bound and never touches
the memory wall it was supposed to relieve. f32 falls from 16 to 7.7 as the matrix leaves
cache, which is why the ratio improves with size; it never crosses 1.0, and 0.67x at
8192x8192 is with 268 MB of weights, already far past any cache on this box.

At 20 threads (`Par.java`, single-accumulator rows) f32 reaches 93 GB/s and f16 still
loses at 0.72x: the decode scales with the cores exactly as the bandwidth demand does.

## JEP 508's `Float16`, measured (`F16.java`)

`jdk.incubator.vector.Float16` (JEP 508, Vector API tenth incubator) is **present in this
JDK 25** -- in `jdk.incubator.vector`, not `java.lang`, which is what an early probe here
got wrong. It does not rescue f16 on this host:

| GEMV, 4 accumulators | Gelem/s at 1024x1024 |
| --- | --- |
| f32 Vector API + FMA | 16.17 |
| hand-vectorized exact decode -> f32 accumulator (`Acc.java`) | 5.27 |
| `Float16.floatValue()` -> f32 accumulator | 2.98 |
| `Float.float16ToFloat` intrinsic -> f32 accumulator | 2.98 |
| `Float16.fma` with a **`Float16` accumulator** | 0.21 |

The JEP auto-vectorizes `Float16` arithmetic on **x64 with AVX512-FP16**; this is aarch64,
so the scalar path runs and the value class is 25x slower than the hand-written decode.
Re-run `F16.java` on an AVX512-FP16 box before concluding anything about x64. There is
still no `Float16` *vector species* anywhere through JEP 537 (JDK 27) -- that is the thing
that would matter, and it is still exploratory in Panama's `vectorIntrinsics+fp16` branch.

## bfloat16 against IEEE f16 (`Worth.java`)

Same GEMV, same 4 accumulators + FMA, same f32 activations; only the weight format
differs. Times are one GEMV; the ratio held to within 0.03x across reuse factors 1 to 64.

| shape | f32 | f16 fused | bf16 fused | f16 vs f32 | **bf16 vs f32** |
| --- | --- | --- | --- | --- | --- |
| 1024x1024 (4 MB, cache-resident) | 0.07 ms | 0.20 ms | 0.08 ms | 0.32x | 0.88x |
| 4096x4096 (67 MB, DRAM-bound) | 1.91 ms | 3.20 ms | 1.25 ms | 0.60x | **1.60x** |

bf16 decoding is `bits << 16` -- bf16 *is* the top half of an f32 -- so it is one
instruction and the halved bytes turn into the bandwidth win. f16 needs the ~6-op bit
trick and the ALU work costs more than the bandwidth it saves. On cache-resident data
there is no bandwidth to save and bf16 gives back a little (0.88x); on anything large
enough to matter it wins.

Round-trip relative error on N(0, 0.02) weights: **f16 1.00e+00, bf16 3.89e-03**. The f16
figure is not a typo -- samples below its 6e-8 subnormal floor underflowed to zero. bf16
carries f32's exponent range so it cannot; it pays in mantissa instead (8 bits vs 11,
3.9e-3 vs 4.9e-4 on the values that survive).

## When "widen once into an f32 scratch" is worth it (`Worth.java`)

The alternative to a fused kernel: decode the whole matrix into an f32 scratch, then run
the existing f32 kernel over it. Its cost is one decode pass amortized over however many
vectors the matrix is multiplied by. At 4096x4096:

| reuse | f32 | f16 widen-once + f32 kernel |
| --- | --- | --- |
| 1 | 1.91 ms | 4.95 ms (0.39x) |
| 8 | 15.99 ms | 17.38 ms (0.92x) |
| 16 | 31.89 ms | 31.91 ms (**parity**) |
| 64 | 127.52 ms | 119.51 ms (1.07x) |

A decode-step GEMV is reuse 1, which is the worst case; batched or prefill work is reuse
= batch size. This is why the fused kernel carries the item and the scratch route is only
a fallback.

## Should this be re-run on x64?

Not before it is needed, and it cannot change the decision. Two things separate:

- **What an x64 run could genuinely move**: only the f16 numbers. x64 has had
  `vcvtph2ps` (F16C, 8 lanes) since 2013, so if HotSpot auto-vectorizes a scalar
  `Float.float16ToFloat` loop there, f16's decode cost could collapse and the f16-vs-bf16
  gap could narrow. Note that openjdk/jdk#22755, the Float16 auto-vectorization work,
  covers *arithmetic* (add/sub/mul/div/sqrt/fma), not the conversions -- so this is a
  question, not a known win. **The bf16 numbers have no such uncertainty**: a left shift
  is a left shift on every ISA, and the Vector API expresses it directly.
- **What it cannot move**: the `Float16`-accumulator kernel, the one thing AVX512-FP16
  would make genuinely fast. `.kb/vec.md`'s lane-count pin already forbids its shape:
  `FSPECIES_REDUCE` is `SPECIES_128` and not `SPECIES_PREFERRED` precisely so "a compiled
  `.class` / native binary must not answer differently on an AVX-512 host". An f16
  accumulator changes the accumulator's *precision*, not merely its lane count, so it
  would break that invariant harder than the case the pin was written for -- at any
  speed, on any host.

So the choice of bf16 is host-independent by construction, and no child item's scope
depends on an x64 number. The useful place for one is item 488, which has to measure the
fused kernel on whatever host it is implemented on anyway; add x64 to its verification
rather than re-running this spike.

Practically: every probe here is a single-file source-launcher program, so an x64 answer
costs one command on any x64 box --
`java --add-modules jdk.incubator.vector Acc.java`, then `F16.java`. Note that a standard
GitHub `ubuntu-latest` runner is unlikely to have AVX512-FP16 (Sapphire Rapids or later),
so CI would answer the F16C question but probably not the `Float16` one.

Accuracy is not the problem. A f16 GEMV over N(0, 0.02) weights lands within 0.02% of the
f64 reference at every size tried, and the f16 round-trip error is bounded by 2^-11 as
expected.

# Round 2, 2026-09-03: the second JIT, the quantized widths, and the load path

Same machine, same GraalVM 25.0.4. Three new probes, each run twice: under the JIT the
box runs by default (**Graal**, which is also what CI and the native image use) and
under **C2** (`java -XX:-UseJVMCICompiler ...`), which is what a user's stock OpenJDK runs
a compiled `.class` under. The first round measured Graal only, and one of its conclusions
does not survive the second JIT.

| file | question it answers |
| --- | --- |
| `Jit.java` | the same bf16 / f16 GEMV in eight JIT-facing shapes -- which shape is fast under BOTH JITs? |
| `Load.java` | what a checkpoint costs to CONVERT at load: f16 -> f32 / bf16, f32 -> bf16, Q8_0 -> bf16, for 1.1B elements |
| `Quant.java` | are the GGUF integer widths (Q8_0, Q4_0) faster than bf16 on the Vector API, at 1 thread and at 20? (`java ... Quant.java par` for 20) |

## 1. The f16 decode verdict was a Graal verdict

Round 1 said the scalar `Float.float16ToFloat` loop "is not auto-vectorized into `FCVTL`
by this JDK". It is not by **Graal**; C2 does it (`Dec.java`, variant A, 4 Mi elements):

| decode variant | Graal Gelem/s | C2 Gelem/s |
| --- | --- | --- |
| A scalar `Float.float16ToFloat` loop | 1.96 | **14.93** |
| B exact bit-trick vector | 3.58 | 4.73 |
| D magic-mul + inf fixup | 5.96 | 6.29 |
| E bf16 shift | 12.86 | 16.25 |
| F f32 -> f32 copy (ceiling) | 10.63 | 10.97 |

So on C2 an f16 array decodes as fast as a bf16 one. It still does not make f16 a compute
width, for the reason in section 3: the fast decode is a *scalar loop* the JIT vectorizes,
and a scalar GEMV loop is a float reduction, which neither JIT reorders. What it does
change is the load path (section 4).

## 2. The spike's own fused kernel collapses under C2 -- the inlining cliff

`Worth.java`'s `rowNarrow` carries the f16 and the bf16 decoder in one method behind a
boolean. Under Graal it measured the round-1 table. Under C2, the same code:

| 4096x4096, reuse 1 | Graal | C2 |
| --- | --- | --- |
| f32 | 1.88 ms | 2.15 ms |
| f16 fused | 3.20 ms (0.59x) | 11.86 ms (**0.18x**) |
| bf16 fused | 1.25 ms (1.51x) | 10.72 ms (**0.20x**) |

Nothing about the arithmetic changed; the method exceeded C2's inlining budget for the
Vector API call chain, the vectors were boxed, and the "1.6x" became a 5x loss. `Jit.java`
has the same bf16 decode in a method of its own and C2 runs it at **2.06x** (below). The
rule this leaves for `.todo/488`: **one small kernel method per width, no shared decoder
behind a flag, and every kernel number taken under both JITs.**

## 3. The kernel shape (`Jit.java`, 4096x4096, 1 thread)

| variant | Graal ms | Graal vs f32 | C2 ms | C2 vs f32 |
| --- | --- | --- | --- | --- |
| f32 lanes (baseline) | 1.851 | 1.00x | 2.045 | 1.00x |
| bf16, `S_64` short -> `convertShape(S2I)` (the spike's shape) | 1.253 | **1.48x** | 0.994 | **2.06x** |
| bf16, `S_128` short -> `convertShape` parts 0/1 | 1.190 | 1.56x | 1.281 | 1.60x |
| bf16, plain scalar loop, 4 accumulators | 5.426 | 0.34x | 4.619 | 0.44x |
| bf16, widen the row into an L1 scratch, then f32 lanes | 2.457 | 0.75x | 3.552 | 0.58x |
| f16, vector magic-multiply decode | 3.177 | 0.58x | 6.717 | 0.30x |
| f16, plain scalar loop, 4 accumulators | 6.595 | 0.28x | 7.289 | 0.28x |
| f16, widen the row into an L1 scratch, then f32 lanes | 9.777 | 0.19x | 1.971 | 1.04x |

**Every row of this table is a 4-accumulator + FMA kernel, and that turns out to be load
bearing -- see section 7 (2026-09-03).** The ratios below hold against a 4-accumulator f32
baseline; against the single-accumulator f32 kernel the project actually ships they are
~1.0x on one thread.

The fused Vector API decode is the shape, on both JITs. The scalar loops lose on both
(a float reduction is never auto-vectorized), and the "scratch" shape -- the one that
would have reused the f32 kernel unchanged -- costs a store and a reload per element and
loses on both too, except f16-under-C2 where the vectorized `FCVTL` loop brings it to
parity with f32. Note `convert()` is NOT the widening op: it preserves the vector SHAPE
(a 64-bit short vector converts to a 64-bit int vector of 2 lanes); `convertShape` is.

## 4. Conversion at load is cheap on either JIT (`Load.java`, 64 Mi elements)

| conversion | Graal Gelem/s | C2 Gelem/s | 1.1B elements, worst JIT |
| --- | --- | --- | --- |
| f16 -> f32, scalar `Float.float16ToFloat` loop | 1.94 | 10.45 | 0.57 s |
| f16 -> f32, exact vector (magic-mul + fixup) | 5.38 | 5.78 | 0.20 s |
| f16 -> bf16, scalar via f32, round-to-nearest-even | 1.36 | 1.52 | 0.81 s |
| f32 -> bf16, vector round-to-nearest-even | 7.34 | 7.26 | 0.15 s |
| Q8_0 -> bf16, scalar | 1.42 | 1.66 | 0.78 s |

Every conversion of a 1.1B-parameter checkpoint is under a second, on the slower JIT,
single-threaded. **So an IEEE f16 file needs no f16 array: it is read as `(unsigned-byte
16)` and widened in bulk into `#f` or `#bf16`** (`.todo/671`). Both vector converters were
checked against the scalar ones (all 65536 f16 patterns; 64 Mi bf16 narrowings): 0
mismatches.

## 5. The integer widths (`Quant.java`)

GEMV, f32 activations, ggml's block size of 32, one f32 scale per block. Q8_0 in two
shapes -- dequantize each lane to f32 and FMA ("q8deq"), and runq.c / ggml's shape where
the activation is quantized to int8 per block too and the dot is integer ("q8int") -- and
Q4_0 (nibbles, value = (n - 8) * scale, the -8 folded out through the block sums of x).
Relative error of the GEMV result against an f64 reference, N(0, 0.02) weights, N(0, 1)
activations: f32 3e-7, **bf16 1.7e-3, q8deq 5.4e-3, q8int 7.6e-3, q4 8.5e-2**.

**1 thread, Graal** (the JIT every number in this repository is taken under):

| shape | f32 MB | f32 ms | bf16 | q8int | q8deq | q4 | bf16/f32 | q8int/f32 | q8deq/f32 | q4/f32 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1024x1024 | 4 | 0.070 | 0.075 | 0.071 | 0.101 | 0.127 | 0.93x | 0.99x | 0.70x | 0.55x |
| 4096x4096 | 67 | 2.147 | 1.256 | 1.092 | 1.570 | 1.949 | 1.71x | **1.97x** | 1.37x | 1.10x |
| 5632x2048 (TinyLlama w1) | 46 | 1.354 | 0.884 | 0.733 | 1.077 | 1.321 | 1.53x | 1.85x | 1.26x | 1.03x |
| 8192x8192 | 268 | 8.524 | 5.003 | 4.266 | 6.217 | 7.317 | 1.70x | **2.00x** | 1.37x | 1.17x |

Effective weight bandwidth at 8192x8192: f32 31.5 GB/s, bf16 26.8, q8 12.1, **q4 5.7**.
bf16 is near the single-core memory wall; Q8 is ALU-bound at ~15 Gelem/s and Q4 at ~9
(the nibble unpack), which is why Q4_0 -- a quarter of the bytes -- is barely faster than
f32 on one thread.

**20 threads, Graal** (`par`; the 46-67 MB rows sit partly in the 20 cores' aggregate L2
and are NOT the decode regime -- read the 268 MB row, which is):

| shape | f32 ms | bf16 | q8int | q8deq | q4 | bf16/f32 | q8int/f32 | q8deq/f32 | q4/f32 | f32 GB/s | bf16 GB/s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4096x4096 | 0.969 | 0.718 | 0.556 | 0.844 | 0.877 | 1.35x | 1.74x | 1.15x | 1.10x | 69 | 47 |
| 8192x8192 | 2.954 | 1.809 | 1.547 | 2.022 | 2.193 | **1.63x** | **1.91x** | 1.46x | 1.35x | 91 | 74 |

**The same under C2**, for the second-JIT check: 1 thread 4096x4096 -- bf16 1.98x, q8int
1.25x, **q8deq 0.12x**, q4 1.05x; 20 threads 8192x8192 -- bf16 1.70x, q8int 1.77x, **q8deq
0.32x**, q4 1.42x. The dequantize-and-FMA shape falls off the same cliff as section 2
(`convertShape(B2I, ..., part)` in an 8-call method); the integer-dot shape survives both
JITs, and it is also the shape whose accumulation is exact integer arithmetic.

What this decides:

- **bf16 stays the width** (`.todo/482`): 1.5-2.1x on one thread on either JIT, 1.6x at 20
  threads, 1.7e-3 error, exact widening, and every current checkpoint is published in it.
- **Q8_0 is worth a type, as a read-only weight matrix, not as a float-array width**
  (`.todo/672`): 2.0x / 1.9x, a quarter of f32's bytes, 7.6e-3 error -- and it is what
  half of the GGUFs on Hugging Face are. Its integer-dot kernel is the C2-safe one.
- **Q4_0 is not a CPU item** on this Vector API: 1.1-1.35x for 8.5% error; the unpack is
  ALU-bound at 5.7 GB/s. The K-quants unpack more, not less. Q4 belongs on the device,
  where a nibble decode is free next to the memory traffic (`.todo/490`'s successor).

Projected per-token cost for TinyLlama-1.1B from the 268 MB rows (GEMV only, an upper
bound on tok/s): 1 thread f32 140 ms / bf16 82 / q8 70 (7 / 12 / 14 tok/s); 20 threads
48 / 30 / 25 ms (21 / 34 / 39 tok/s). `.todo/489`'s estimates stand.

## 6. What the checkpoints actually are (checked 2026-09-03)

`model.safetensors` headers read over HTTP: `HuggingFaceTB/SmolLM2-135M` 272 tensors, all
BF16; `TinyLlama/TinyLlama-1.1B-Chat-v1.0` 201 tensors, all BF16 (`lm_head.weight`
[32000, 2048], `down_proj` [2048, 5632]); `Qwen/Qwen2.5-0.5B` 290 tensors, all BF16
(`embed_tokens` [151936, 896]). No f16 anywhere in a current small model. GGUF carries
the same models as BF16 / F16 / Q8_0 / Q4_K_M, with the tokenizer and the hyperparameters
in the same file; the GGUF already in this box's Hugging Face cache is a Q4_K_XL model
with a BF16 `mmproj` beside it (BF16 weights, F32 biases). That is the input side of
`.todo/670`.

## 7. The one-thread ratios are conditional on the accumulator count (2026-09-03)

Written when `.todo/488` implemented the kernels against the shipped f32 GEMV and the
numbers did not reproduce. Nothing above is withdrawn -- it was measured as stated -- but
it needs a condition attached, because a reader was taking "bf16 is 1.5-2.1x on one thread"
as a property of the width and it is not.

**Every kernel measured in rounds 1 and 2 -- both arms -- uses four accumulators and FMA**
(`Acc.java`, `Worth.java`, `Jit.java`; round 1's file table says so of the f16 work, and
`Jit.java`'s `rowF32` is the f32 baseline of section 3). **The kernels rontolisp ships use
ONE accumulator and a two-rounding mul-then-add**, because a single `f32x4` chain is the
cross-backend bit-identity contract of the f32 reductions (`.kb/vec.md`, "The lane-count
pin") and `.todo/480` -- the item that would change it -- had not landed.

Measured against the shipped kernels, at 4096x4096, one thread (provisional: a smoke run
beside two busy lanes; `.todo/488-the-fused-bfloat16-gemv-kernels/README.md` has the full
tables and will carry the quiet-window numbers):

| 4096x4096, 1 thread, bf16 vs f32 | Graal | C2 |
| --- | --- | --- |
| section 3's shape, 4 accumulators + FMA, re-derived | 1.59x | 1.97x |
| the same decode against the SHIPPED single-accumulator f32 kernel | 0.80x | **1.02x** |

The re-derivation lands on section 3's 1.48x / 2.06x, so the decode is not in question;
the accumulator count is the whole difference. The reason is that one accumulator is one
dependency chain, which bounds the row at 5.5-7.6 Gelem/s -- short of the memory wall. bf16
saves bandwidth, and a latency-bound kernel has no bandwidth to save.

So, restated with its condition:

- **One thread: bf16 is 1.5-2.0x of f32 in a 4-accumulator kernel, and ~1.0x in a
  single-accumulator one.** The single-thread win is `.todo/480`'s to unlock; until it
  lands, bf16 buys memory, not speed, on one thread.
- **20 threads: bf16 wins on the shipped kernels as this file said** -- 1.07-1.56x
  measured across runs of the same code, against section 5's 1.63x / 1.70x. Spreading the
  rows across cores is what lifts the accumulator chain off the critical path, so the
  parallel arm reaches the bandwidth regime the serial one does not.
- **Nothing changes about the format choice.** f16's decode cost, the widen-into-scratch
  route and the quantized widths were all measured with the same accumulator count on both
  sides, so those comparisons are unaffected; and the exactness, error and load-path
  results are arithmetic, not throughput.

`.todo/480` is therefore a prerequisite of `.todo/488`, not an independent optimization,
and both items now say so.
