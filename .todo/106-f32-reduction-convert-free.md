# 106 — make the `#f` reduction kernels conversion-free

**Status: analysed, prototyped, NOT implemented.** A standalone Java prototype (below) proves the fix
works. Everything needed to do it in a fresh session is in this file.

**Documentation policy (user, 2026-07-09):** the JVM-specific findings here are *our measurements on
one machine*, not vendor-documented behavior, and may not match what the vendor guarantees or ships
next. **They must not go into `doc/**` or the example headers**, which say only "whether the Vector
API bridge becomes CPU instructions is up to the JVM; measure". Keep the JVM names here.

## The problem in one line

The `#f` (single-float) **reduction** kernels widen every `f32` lane to `f64` before accumulating,
via `FloatVector.convert(VectorOperators.F2D, part)`. That widening is the single most expensive
thing in them, and it is the operation most likely to be missing from a JIT's Vector API intrinsics.

The widening exists so an `#f` reduction is bit-identical to the scalar `vec.lisp` reference, which
reads each element into a `double` and accumulates in `double`.

### Three layers — do not conflate them

1. **The cliff** (per-lane emulation, 40-140x) appears only on a JIT that lacks the `convert`
   intrinsic. Observed on one compiler family; unofficial; could change in any release.
2. **The conversion is never free.** Even where it IS intrinsified, `#f` dot is ~2x SLOWER than `#d`
   dot despite having twice the lanes — the opposite of the point of `f32x4`.
3. **The WASM `--simd` backends do not have this problem at all.** `WasmVecLoops`: "each width
   computes entirely in its own native precision; the reductions promote to f64 only at the value
   boundary." No per-element widening on the hot path.

So this is not a JIT bug. It is our kernel's precision contract meeting a JIT's intrinsic coverage,
and only the two Vector-API kernels are affected.

## Can it be fixed in the kernel? YES — but the precision contract has to give

The exact product of two `f32` needs 48 mantissa bits, so it only fits in an `f64` lane; and the only
way to move `f32` data into `f64` lanes in the Vector API is `convert`/`convertShape`. **Bit-identity
with the f64-accumulating scalar reference therefore forces the conversion.** No kernel trick avoids
it.

But that contract is **already broken across backends today** (see the probe results below), so we are
paying its full cost without getting what it buys.

## PROTOTYPE — the fix works (measured 2026-07-09, Apple M4, 1024 floats x 40000 reps = 40.96M MAC)

| kernel | GraalVM JIT | Liberica 25 (HotSpot) |
|---|---|---|
| **A** `convert(F2D)` + `DoubleVector` accumulator — *what we ship today* | 2296 ms | 14 ms |
| **B** multiply + accumulate in `FloatVector`, promote once at the reduce — *proposed* | **4 ms** | **4 ms** |
| C plain scalar loop with a `double` accumulator (no Vector API) | 26 ms | 21 ms |

B is 574x faster than A on one JIT, 3.5x faster on the other, and **both JVMs converge on the same
4 ms**. B is exactly what the WASM kernels already do. Its precision probe result (16777984) is
bit-for-bit what wasm-GC `--simd` prints today.

Prototype source — save as `DotBench.java`, run with
`java --add-modules jdk.incubator.vector DotBench.java`:

```java
import jdk.incubator.vector.*;

public class DotBench {
    static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
    static final VectorSpecies<Double> DS = DoubleVector.SPECIES_PREFERRED;

    // A: today's kernel (VecSimdKernels.dotF)
    static double dotConvert(float[] x, float[] y) {
        int n = x.length, i = 0;
        DoubleVector vacc = DoubleVector.zero(DS);
        int bound = FS.loopBound(n);
        for (; i < bound; i += FS.length()) {
            FloatVector fx = FloatVector.fromArray(FS, x, i);
            FloatVector fy = FloatVector.fromArray(FS, y, i);
            DoubleVector x0 = (DoubleVector) fx.convert(VectorOperators.F2D, 0);
            DoubleVector x1 = (DoubleVector) fx.convert(VectorOperators.F2D, 1);
            DoubleVector y0 = (DoubleVector) fy.convert(VectorOperators.F2D, 0);
            DoubleVector y1 = (DoubleVector) fy.convert(VectorOperators.F2D, 1);
            vacc = vacc.add(x0.mul(y0)).add(x1.mul(y1));
        }
        double acc = vacc.reduceLanes(VectorOperators.ADD);
        for (; i < n; i++) acc += (double) x[i] * (double) y[i];
        return acc;
    }

    // B: conversion-free (what the WASM kernels already do)
    static double dotFloatAcc(float[] x, float[] y) {
        int n = x.length, i = 0;
        FloatVector vacc = FloatVector.zero(FS);
        int bound = FS.loopBound(n);
        for (; i < bound; i += FS.length())
            vacc = FloatVector.fromArray(FS, x, i).mul(FloatVector.fromArray(FS, y, i)).add(vacc);
        float acc = vacc.reduceLanes(VectorOperators.ADD);
        for (; i < n; i++) acc += x[i] * y[i];
        return acc;
    }
    // ... plus a warm-up loop, a timing loop, and the precision probe below.
}
```

## THE PROBE — before / after, the thing to check the improvement with

`v = #f(4096.0 1.0 1.0 ... 1.0)`, 1024 elements. `dot(v,v) = 4096^2 + 1023 = 16778239` exactly.
`4096^2` is `2^24`, where the f32 spacing is 2, so a single-precision accumulator swallows the ones.
With `L` lanes the accumulated value is `2^24 + (L-1) * (1024/L)`.

Save as `probe32.lisp` (and `probe64.lisp` with `(vec:ones 1024)` for the `#d` control):

```lisp
(defparameter *v* (vec:ones 1024 'single-float))
(setf (aref *v* 0) 4096.0)
(format t "f32 dot = ~a~%" (round (vec:dot *v* *v*)))
```

```bash
rontolisp probe32.lisp                                      # scalar
rontolisp probe32.lisp --simd                               # interpreter kernels
rontolisp probe32.lisp -o A.class && java -cp . A           # jvm scalar
rontolisp probe32.lisp -o As.class --simd && java --add-modules jdk.incubator.vector -cp . As
rontolisp probe32.lisp -o a.wasm  && wasmtime run -W gc a.wasm
rontolisp probe32.lisp -o as.wasm --simd && wasmtime run -W gc as.wasm
```

### CURRENT (measured 2026-07-09) — exact answer is 16778239

| backend | `#f` scalar | `#f` `--simd` | `#d` scalar | `#d` `--simd` |
|---|---|---|---|---|
| interpreter | 16778239 | 16778239 | 16778239 | 16778239 |
| JVM class | 16778239 | 16778239 | 16778239 | 16778239 |
| wasm-GC | 16778239 | **16777984** | 16778239 | 16778239 |
| wasm component | 16778239 | **16777984** | 16778239 | 16778239 |

**wasm-GC `--simd` already disagrees with every other backend on `#f` reductions** — by 255, about
one single-float epsilon relative. Nobody noticed because no test exercises a value near `2^24`.
`#d` is exact everywhere and must stay that way.

### EXPECTED AFTER the fix (option 1 below)

| backend | `#f` scalar | `#f` `--simd` | `#d` scalar | `#d` `--simd` |
|---|---|---|---|---|
| interpreter | 16778239 | **16777984** | 16778239 | 16778239 |
| JVM class | 16778239 | **16777984** | 16778239 | 16778239 |
| wasm-GC | 16778239 | 16777984 | 16778239 | 16778239 |
| wasm component | 16778239 | 16777984 | 16778239 | 16778239 |

That is: **every `--simd` backend agrees with every other**, the scalar reference stays the most
accurate oracle, and `#d` is untouched. The contract becomes explicit: *`--simd` accumulates an `#f`
reduction in single precision.* (Do NOT also change the scalar `vec.lisp` reference to accumulate in
f32: a 1-lane f32 accumulator would print 16777216 — less accurate than the 4-lane one, and it would
still not equal the `--simd` value.)

### The lane-count trap — read before writing code

`16777984` assumes **4 lanes**. `FloatVector.SPECIES_PREFERRED` is 4 lanes on a 128-bit machine
(NEON, this M4) but 8 on AVX2 and 16 on AVX-512, giving 16778112 and 16778176. The WASM kernels are
always `f32x4`. So after the fix the JVM/interpreter `#f` reduction result would depend on the host
CPU, and cross-backend agreement would hold only on 128-bit machines.

**Recommendation: pin the `#f` REDUCTION kernels to `FloatVector.SPECIES_128`** (leave the
element-wise kernels on `SPECIES_PREFERRED`, they are bit-exact regardless of width). Then all four
`--simd` backends give 16777984 on every host. Cost: on a wide machine an `#f` reduction uses only
128-bit vectors — still vastly faster than today's emulated path, and determinism is a core promise
of this project (`ci-spec.yaml`, the byte-identity oracle). Verify the perf cost of the pin on an
AVX-512 box before ruling it out; it was not measurable here (M4 is 128-bit).

## Where the code is

Exactly **four methods in each of two files** (8 `convert(F2D)` sites total):

| file | methods |
|---|---|
| `src/main/java/am/ik/rontolisp/eval/VecSimdKernels.java` | `sumF` (L438), `dotF` (L459), `matvecF` (L486), `matvecIntoF` (L346) |
| `src/main/java/am/ik/rontolisp/codegen/jvm/JvmSimdVectorTemplate.java` | `sumF` (L670), `dotF` (L699), `matvecF` (L737), `matvecIntoF` (L557) |

The two files are deliberate duplicates (`eval` may not depend on `codegen.jvm`) — change both.
Their class javadoc (`VecSimdKernels` L22, `JvmSimdVectorTemplate` L44) documents the old contract
and must be rewritten. `vec:mean` / `vec:norm` derive from `sum` / `dot` and follow automatically.

**Do not touch**: `WasmVecLoops`, `WasmVecSimdRuntimeBuilder`, the `--no-gc` kernels (they emit v128
directly, no Vector API, already conversion-free), or the element-wise `addF`/`subF`/`mulF`/`scaleF`
(no conversion; `#f` there is already *faster* than `#d`).

## Tests

- `eval/VecSimdTest.dotAndSumMatchTheScalarOracleForSingleFloatVectors` asserts `--simd` equals the
  scalar oracle exactly for `(vec:arange 200 'single-float)`. Its value (sum of i^2 for i<200 =
  2646700) is under `2^24`, so it is exact in f32 too and **will keep passing**. It does not pin the
  contract. `codegen/jvm/JvmSimdAccelCompilerTest` is the compiled-path sibling.
- **Add the probe above as the pinning test**, on all four backends, with and without `--simd`. It is
  the only thing that will catch a regression in either direction.
- `ci-spec.yaml` never passes `--simd`, so the cross-backend E2E is unaffected.
- `examples/ml/simd-dot.lisp` is `#d` on purpose and must not change. `simd-gemv.lisp`, `nn-vec.lisp`
  and the parked `tiny-llm.lisp` are `#f` — their printed output is `argmax` indices / token ids,
  robust to an f32-precision reduction, but re-run them: they are the realistic regression check.

## Performance targets after the fix

`vec:dot`, 40.96M multiply-adds, from the Lisp level (`(vec:arange 1024 ...)`, 40000 reps):

| runtime | `#d` today | `#f` today | `#f` target |
|---|---|---|---|
| native binary | 29 ms | 4198 ms | <= `#d` |
| GraalVM JIT (jar) | 59 ms | 2357 ms | <= `#d` |
| Liberica 25 (jar) | 49 ms | 106 ms | <= `#d` |

`#f` should end up **faster** than `#d` (4 lanes vs 2), as it already is for `vec:add`
(native 24 vs 41 ms). If it does not, the conversion is still on the critical path somewhere.

## Options considered

1. **Adopt the WASM contract on the JVM/interpreter kernels** (recommended, prototyped above).
2. **Chunked reduce**: multiply in `FloatVector`, `reduceLanes` each chunk to a float, add into a
   `double`. No `convert`; precision between (1) and today; still not bit-identical to the scalar
   reference, and it makes the result depend on the chunk count as well as the lane count. Strictly
   worse than (1) for determinism.
3. **Leave it, document it** — where we are now. Both guides tell the user: if a single-float
   reduction is slower with `--simd`, the widening is why; use `#d` when a reduction must agree to
   the last bit.
4. Detect the runtime and pick a kernel. Rejected: unknowable at build time, and the interpreter's
   kernels are baked into the native image.

## Related

Measurement log and the story of how this was found: `.todo/98`. Memory:
`native-image-vector-api.md`, `jvm-scalar-numeric-is-allocation-bound.md`. Mechanics: `.kb/vec.md`.
