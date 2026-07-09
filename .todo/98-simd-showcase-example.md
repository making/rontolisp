# 98 — an example that actually shows the `--simd` win

**Documentation policy (user, 2026-07-09):** everything in this file about how a particular JVM
behaves is *our measurement*, not vendor-documented behavior, and may not match what the vendor
officially guarantees or does in the next release. **Do not put it in `doc/**` or in the example
headers.** Those say only: whether the Vector API bridge becomes CPU instructions is up to the JVM
that runs the class, so measure. Keep the JVM names, ablations and numbers here and in `.todo/106`.

**Motivation:** we have a real SIMD acceleration layer on every backend, and (until now) no example
where it pays off. `examples/ml/nn-vec.lisp`'s tensors are rows of length 2 and 4 — far below the
JVM/interpreter `THRESHOLD = 128`, so `--simd` does literally nothing there.

## DONE 2026-07-09 — `examples/ml/simd-dot.lisp` (the minimal one)

Eight lines of code: `(vec:dot v v)` over `(vec:arange 1024)`, four thousand times. The vector holds
`0.0 .. 1023.0`, so the dot is the exact integer 357389824 and every partial sum is exactly
representable in a double — lane-order folding cannot move it, so the printed answer is identical on
all four backends with and without `--simd`, and the E2E `contains` check is safe. Deliberately `#d`,
not `#f`: see FINDING A+B below — an `#f` version would be 100x slower under `--simd` on GraalVM.

Interpreter 2.59 s -> 2.3 ms (**1100x**), wasm-GC 273 ms -> 2.4 ms (115x). A compiled class runs it
in a few tens of ms either way (too short for a JIT). Registered in `examples.yaml` +
`examples/README.md`; linked from both guides.

## DONE 2026-07-09 — `examples/ml/simd-gemv.lisp`

The minimal example shipped: a hundred steps of "project a vector through a 256x256 single-float
matrix, rescale to unit RMS, repeat" — nothing but `vec:matvec` (GEMV) and `vec:dot`, the two
kernels an autoregressive LLM spends nearly all its time in. It prints `argmax` **indices**, not
floats, so the output is identical with and without acceleration and across every backend.

Registered in `examples/examples.yaml` (`[interpreter, jvm, wasm]`, `contains` on the two header
lines and the two argmax lines; the elapsed line is deliberately unchecked) and in the
`examples/README.md` ml table (which also gained the `nn-vec.lisp` row it was missing).
`ExamplesE2eTest` 86/0. `doc/{en,ja}/guides/simd-acceleration.md` gained a "A runnable example"
section pointing at it, and **FINDING A below is now fixed there** — the JVM bullet names the JIT.

Verified identical on all ten configurations (interpreter / JVM × 2 JITs / wasm-GC / wasm component,
each with and without `--simd`):

```
argmax after steps 1-10: (0 14 82 126 14 140 126 79 134 175)
argmax after step 100:   85
```

Elapsed, Apple M4:

| config | scalar | `--simd` | ratio |
|---|---|---|---|
| **wasm-GC** | 467 ms | **3.9 ms** | **120x** |
| wasm component | 479 ms | 4.3 ms | 111x |
| **interpreter** (native binary) | 4.67 s | **0.68 s** | **6.9x** |
| JVM on HotSpot (Liberica 25) | 201 ms | 95 ms | 2.1x (JIT still warming up at this size) |
| JVM on HotSpot (Liberica 25), `*steps*` 400 | 489 ms | 115 ms | **4.3x** |
| JVM on **GraalVM JIT** (the default `java`) | 50 ms | 515 ms | **0.10x — SLOWER** |
| JVM on GraalVM JIT, `*steps*` 400 | 69 ms | 1521 ms | 0.05x — SLOWER |

### Cross-JVM confirmation (2026-07-09, Liberica 25.0.3 via sdkman, same `.class` files)

Liberica 25 is plain HotSpot, no JVMCI. It reproduces `-XX:-UseJVMCICompiler` on GraalVM almost
exactly (400 steps: scalar 485-504 vs 476, `--simd` 114-116 vs 112), which independently confirms
that "C2 intrinsifies, Graal does not" — it is the compiler, not a GraalVM packaging quirk. The
GraalVM JIT's `--simd` time also scales *linearly with work* (515 ms at 100 steps, 1521 ms at 400),
the signature of per-lane emulation, while its scalar time barely moves (49 -> 69 ms — NOT because
it vectorizes; see "Why GraalVM's scalar path is the fastest" below).

The fingerprint is identical on all three JVMs.

## MEASURED FACTS — do not re-derive these

All on an M4 (aarch64). Pure kernel, 200 × `vec:dot` over 1048576 f32 elements (209.7M MACs):

| runtime | time | ns/MAC |
|---|---|---|
| jar + `-XX:-UseJVMCICompiler` (C2) + Vector API | **79 ms** | 0.38 |
| jar + GraalVM JIT + Vector API | 11482 ms | 55 |
| native binary (`-H:+VectorAPISupport` IS on the command line) + Vector API | 21671 ms | 103 |
| interpreter scalar (C2) | 65039 ms | 310 |

### FINDING A+B, SOLVED 2026-07-09: GraalVM does not intrinsify the lane-widening `convert(F2D)`

Both findings below were measured on `#f` (single-float) programs, and both had the same single
cause. **GraalVM — JIT and Native Image alike — intrinsifies `FloatVector` and `DoubleVector` fine.
What it does not intrinsify is `FloatVector.convert(VectorOperators.F2D, part)`**, the lane-widening
conversion. (oracle/graal#10285 lists load/store/arithmetic/reduce/compare/blend; conversions are
absent.) One unsupported op drops the entire loop to per-lane emulation.

rontolisp's `#f` **reduction** kernels all widen f32 -> f64 before accumulating, to match the scalar
reference bit for bit (`VecSimdKernels.dotF` L459-462: four `convert(F2D, ...)` per iteration; the
same in `sumF`, and `matvecF` is a dot per row). So every `#f` `dot`/`sum`/`matvec` hits it. `#d`
kernels have no conversion, and the `#f` **element-wise** kernels (`addF` etc: load, add, store) have
none either.

`vec:dot`, 40.96M multiply-adds, steady state:

| runtime | `#d` (`f64x2`) | `#f` (`f32x4`) |
|---|---|---|
| HotSpot C2 (Liberica 25) | 49 ms | 106 ms |
| GraalVM JIT | 59 ms | **2357 ms** |
| native binary (GraalVM AOT, `-H:+VectorAPISupport`) | **29 ms** | **4198 ms** |

Control that isolates the conversion — `vec:add`, same sizes, no `convert` in the kernel. `#f` is
now *faster* than `#d` on every runtime (4 lanes vs 2), with no emulation cliff:

| runtime | `#d` | `#f` |
|---|---|---|
| native binary | 41 ms | **24 ms** |
| GraalVM JIT | 68 ms | 55 ms |
| Liberica | 57 ms | 49 ms |

**Consequences, all now fixed in the docs:**
- `-H:+VectorAPISupport` is **not inert**. The native binary hits 0.71 ns/MAC on `#d` — the fastest
  `vec:dot` of any configuration measured, beating HotSpot (it has no JIT warmup). The old FINDING B
  ("the flag buys nothing") is withdrawn; memory `native-image-vector-api.md`'s standalone
  `VecBench.java` result is reconciled (its float dot presumably accumulated in float, no `convert`).
- "GraalVM does not intrinsify the Vector API" (old FINDING A) is **too broad**. It fails only on the
  widening conversion. The 11482 ms vs 79 ms pure-kernel figure was an `#f` dot.
- Every earlier `--simd`-is-slow-on-Graal result — tiny-llm, simd-gemv, the pure-kernel bench — used
  `#f`. That is why they all agreed.

**Open follow-up: `.todo/106`** — make the `#f` reductions conversion-free. Prototyped and proven
(a `FloatVector` accumulator runs the same kernel in 4 ms on BOTH JVMs, vs 2296 ms and 14 ms for the
widening version). It also turns out wasm-GC `--simd` already accumulates `#f` in single precision
and therefore already disagrees with the other backends, so the widening is buying us nothing. That
todo has the prototype, the before/after probe, the lane-count trap, and the exact call sites.

### Why GraalVM's scalar path is the fastest configuration of all (measured 2026-07-09)

`-o Prog.class` with no `--simd`, run on the GraalVM JIT, beats *every* `--simd` configuration
(tiny-llm decode: 68 ms, vs 116 ms for C2 `--simd`). That looked like auto-vectorization. It is not.

**The compiled scalar numeric loop is allocation-bound, not FLOP-bound.** `javap` of the generated
class shows `vec:matvec`'s inner loop calling `_fvAref2` / `_fvAref1` (both `-> java.lang.Object`,
i.e. `Double.valueOf` per element read), `_mul(Object,Object)` and `_add(Object,Object)` (each
boxing its result), and `Long.valueOf` per loop step — **~5 heap allocations per multiply-add**,
717 `*.valueOf` call sites in the class. Confirmed in source: `JvmFloatArrayRuntimeBuilder`
`emitAref1Body`/`emitAref2Body` end in `Double.valueOf` + `areturn`; `JvmNumericRuntimeBuilder`'s
`_add`/`_mul` are `(Object,Object)->Object` with `DADD`/`DMUL` then `Double.valueOf`.

Total allocation for the whole run, measured with `-XX:+UseEpsilonGC -Xmx12g -Xlog:gc` (a GC that
never frees, so heap-used-at-exit == bytes allocated):

| config | decode | allocated |
|---|---|---|
| Graal JIT scalar | 68 ms | 307 MB |
| Graal JIT scalar, `-Djdk.graal.PartialEscapeAnalysis=false` | 145 ms | 1142 MB |
| Liberica/C2 scalar | 270 ms | 2535 MB |
| Liberica/C2 `--simd` | 116 ms | 389 MB |

Time tracks bytes allocated across all four. Graal's win is **box elimination**, and it decomposes
into two mechanisms (an adversarial review pinned the split, do not credit it all to PEA):

- **Inlining + box/unbox cancellation**, ~62% of the gap vs C2. Even with PEA OFF, Graal allocates
  2.2x less than C2 (1142 vs 2535 MB). The `_fvAref*` / `_add` / `_mul` helpers are `invokestatic`,
  so there is nothing to devirtualize; Graal inlines them and the `Long` index box/unbox cancels.
- **Partial escape analysis**, the remaining ~38%. The clean within-Graal ablation is 145 -> 68 ms
  and 1142 -> 307 MB: PEA scalarizes the four per-multiply-add `Double` temporaries.

`--simd` is a *second, independent* way to delete the same boxing — it swaps the kernels for
primitive `double[]`/`float[]` loops (`JvmSimdVectorTemplate.simdMatvec` casts once, accumulates in
a primitive `double`, zero boxing per row, one result array per GEMV) — which is why it takes C2
from 2535 MB to 389 MB. The ideal, Graal's de-boxing *plus* an intrinsified Vector API, is exactly
what FINDING B says we are not getting.

**Graal is not auto-vectorizing.** `-Djdk.graal.Vectorization=false` changes nothing (68 -> 69-71 ms),
though that flag alone is weak evidence (it is `[enterprise edition]`-tagged). The decisive arguments
are independent of it: Graal's scalar path runs at 4.2 ns per multiply-add (~17 cycles on an M4
P-core) against 0.38 ns/MAC for a genuinely vectorized f32 dot — an 11x gap, i.e. plainly scalar —
and the emitted loop is boxed `Object` dispatch, which cannot be vectorized before the boxes go away.

**Corollary: `THRESHOLD = 128` is compared against the row length**, not the total element count
(`JvmSimdVectorTemplate.simdMatvec`: `if (n >= THRESHOLD)` where `n` is the column count). A matrix
with many short rows runs the scalar tail for every row no matter how big it is.

### The `#f`-only evidence that led to FINDING B (kept for the record)

Run `simd-gemv.lisp` (single-float) on the *interpreter* — identical `eval/VecSimdKernels` code
path, no compilation, only the JVM underneath differs:

| interpreter `--simd`, 100 steps, `#f` | scalar | `--simd` | ratio |
|---|---|---|---|
| jar on Liberica 25 (HotSpot) | 2650 ms | **91 ms** | **29x** |
| jar on GraalVM JIT | 2846 ms | 548 ms | 5.2x |
| native binary (Graal AOT, `-H:+VectorAPISupport`) | 4706 ms | 673 ms | 7.0x |

Read alone this says "the flag is inert". Swap the vectors to `#d` and the native binary becomes the
fastest runtime of all. The lesson: **every one of these programs was single-float**, and no
experiment varied the element width until `simd-dot.lisp` (a `#d` program) came out 1100x faster on
the interpreter and blew the hypothesis up. Vary the axis you are not thinking about.

## Hard constraints for any `--simd` example (learned the hard way)

- **`THRESHOLD = 128`** (`JvmSimdVectorTemplate`, `eval/VecSimdKernels`): below 128 elements per
  row/vector the JVM and interpreter run a scalar loop. wasm-GC and `--no-gc` have no threshold.
- **Print only INTEGERS.** The WASM backend prints floats to ~7 significant digits
  (`2.718281` vs `2.7182818284590455`), and its `exp` differs from the JVM's in the low bits
  (`exp 10.0` -> `22026.465767` vs `22026.465794806718`). So a float never compares across backends.
  `argmax` of a vector is the trick: an integer that depends on every multiply-add, yet unmoved by
  the last-ULP differences lane-order summation introduces.
- `get-internal-real-time` = **milliseconds**; an INTEGER on interpreter/JVM, a FLOAT on WASM.
  `internal-time-units-per-second` does NOT exist. So an elapsed-time line must not be checked by
  `examples.yaml`.
- `--no-gc` cannot compile `linalg:` at all (`&optional` in `linalg::%la-make`) and rejects
  `vec:matvec`. Any example using either is `[interpreter, jvm, wasm]` only. `simd-gemv.lisp` avoids
  `linalg:` entirely — `(make-array (list r c) :element-type 'single-float)` is enough for a packed
  rank-2 matrix — but `vec:matvec` still rules `--no-gc` out.
- Interpreter budget: existing examples run 0.0 s (heat3d) .. 4.7 s (simd-gemv) .. 10.1 s
  (deep-digits) .. 38.4 s (mlp).
- `ExamplesE2eTest` can fail spuriously when the GraalVM JIT prints a "Systemic Graal compilation
  failure" warning onto the program's stdout (seen once on `ml/deep-digits.lisp: jvm`). Re-run.

## Parked: `examples/ml/tiny-llm.lisp` (untracked, works, NOT registered)

A complete 2-layer transformer decoder: RMSNorm, Q/K/V GEMVs, causal self-attention over a KV cache,
softmax, output projection, SwiGLU FFN, residuals, classifier head, greedy argmax decode. The one
idea in it worth keeping is the **KV cache layout**: the K cache is row-major `(n-ctx x dim)` so
`(vec:matvec kc q)` yields every attention score in one GEMV, and the V cache is **transposed**
`(dim x n-ctx)` so `(vec:matvec vt a)` is the attention-weighted value sum, again one GEMV. Store V
row-major and that step degrades to a scalar loop. llama2.c makes the same choice.

Verified: it prints `generated: (39 27 23 18 42 7 5 39 27)` identically on every configuration tried
(interpreter / JVM x 3 JVMs / wasm-GC / wasm component, each with and without `--simd`).
Interpreter run: ~12.6 s total (1.4 s init + 11.2 s decode). Decode-only timings, Apple M4:

| config | scalar | `--simd` | ratio |
|---|---|---|---|
| wasm-GC | 891 ms | 7.8 ms | 114x |
| **interpreter, jar on Liberica 25 (HotSpot)** | 6227 ms | **104 ms** | **60x** |
| interpreter, jar on GraalVM JIT | 7111 ms | 978 ms | 7.3x |
| interpreter, native binary (Graal AOT) | 11485 ms | 1669 ms | 6.9x |
| compiled class on Liberica 25 | 269 ms (median 270) | 116 ms | 2.3x |
| compiled class on GraalVM `-XX:-UseJVMCICompiler` | 269 ms (lower mode) | 120 ms | 2.2x |
| compiled class on GraalVM JIT | 67 ms | 1015 ms | 0.07x — SLOWER |

Two things fall out of this table, both worth keeping:

1. **It sharpens FINDING B to a 16x gap.** The same `eval/VecSimdKernels` runs the decode in 104 ms
   on HotSpot and 1669 ms in our native image. (`simd-gemv.lisp` showed 7.4x; tiny-llm is more
   GEMV-dense, so the gap widens.) Nothing but the compiler differs.
2. **The interpreter with `--simd` (102 ms median) beats the compiled class with `--simd` (116 ms)
   — but only because of JIT warmup.** tiny-llm's interpreter run spends 1.4 s generating weights
   before decode starts, so the JIT is hot; the compiled class enters decode cold. Amortize the
   warmup (`simd-gemv.lisp` at 400 steps) and the compiled class wins as it should: 106-109 ms vs
   the interpreter's 127-130 ms. At 100 steps they tie (93-95 vs 91-92). **Do not** repeat "the
   interpreter beats the compiler" as a standalone fact.

### Retracted: "Liberica's C2 is ~1.45x faster than GraalVM's `-XX:-UseJVMCICompiler`"

Wrong, and worth remembering as a class of error. The GraalVM `-UseJVMCICompiler` scalar timing is
**bimodal** — nine samples: `226 269 269 271 271 381 383 395 400` — and its lower mode is exactly
Liberica's (269-271). The original claim came from two samples that happened to land in the upper
mode, then called it "stable across samples". Take N>=9 samples and print them all before asserting
that two configurations differ; a median alone would also have hidden the bimodality.

The `--simd` trio (interpreter 101-103, compiled Liberica 114-118, compiled GraalVM `-UseJVMCI`
114-124) likewise overlaps: the last two are the same compiler and must not be ranked against
each other.

Decide: keep it as a second, heavier example (it would need an `examples.yaml` entry and would add
~13 s to the interpreter leg of `ExamplesE2eTest`), or delete it now that `simd-gemv.lisp` ships.
