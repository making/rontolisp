# The `short-float` (f16) spike, 2026-08-22

Throwaway probes kept for reproducibility, NOT project code: outside `src/`, not in the
reactor, not formatted by `spring-javaformat:apply`, and nothing builds or tests them.
They exist so the numbers in `../482-short-float-a-storage-only-narrow-width.md` can be re-derived
on other hardware -- above all the one number that decides the whole shape of the item:
**an f16 array is never faster to compute over on the JVM.**

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

Accuracy is not the problem. A f16 GEMV over N(0, 0.02) weights lands within 0.02% of the
f64 reference at every size tried, and the f16 round-trip error is bounded by 2^-11 as
expected.
