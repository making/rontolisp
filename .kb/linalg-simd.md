# `linalg:` kernel interception (`--simd`)

The `--simd` acceleration of the `linalg:` package (todo-107, 2026-07-10). Read `.kb/vec.md`
first: this is "do what `vec:` does, for `linalg:`", and it reuses the `vec:` lane loops
rather than copying them. `.kb/linalg.md` has the semantics of the library being
accelerated.

Three backends, one per interception mechanism:

| backend | interceptor | kernels |
|---|---|---|
| interpreter (`prog.lisp --simd`) | `eval/LinalgSimd` (re-`defineFunction`) | `eval/LinalgSimdKernels` (jdk.incubator.vector) |
| JVM (`-o Prog.class --simd`) | `codegen/jvm/JvmLinalgSimdCompiler` (call site) | `JvmSimdVectorTemplate.la*` (the one embedded bridge) |
| wasm-GC (`-o prog.wasm --simd`) | `codegen/wasm/WasmLinalgSimdCompiler` (call site) | `WasmLinalgSimdRuntimeBuilder` (15 emitted functions) |

`--no-gc` is out of scope: `linalg:` cannot compile there at all (`linalg::%la-make` uses
`&optional`, and `--no-gc` has no general array type).

The user-facing description lives in `doc/{en,ja}/guides/simd-acceleration.md` (the `--simd`
guide, shared with `vec:`). Keep the intercepted set, the declined-input fallback and the
precision contract in sync with its "Accelerating linalg" section -- but the JVM-specific
measurements below stay here, out of `doc/**`.

## Why: `--simd` used to make `linalg:` SLOWER on wasm-GC

`--simd` switches the packed float-array representation to a `TYPE_VBLOCK` over an
`(array (mut v128))` of lane groups (todo-105). Every *scalar* element read/write then goes
through `_v_get` / `_v_set` -- an `array.get` plus an immediate-lane `if`-chain, and for a
write a whole-group read-modify-write. The `vec:` kernels are intercepted at their call
sites and never pay it. Not one `linalg:` function was, so linalg paid the new
representation's cost and got nothing back. Measured on an M4 / wasmtime 46, the same
rank-1 `#f` arrays (40000 elements, 60 reps):

| wasm-GC | scalar | `--simd` before | `--simd` after |
|---|---|---|---|
| `vec:add` (always intercepted) | 211 ms | 1 ms | 1 ms |
| `linalg:add` | 215 ms | **236 ms** | **1 ms** |
| `vec:dot` (always intercepted) | 119 ms | 1 ms | 1 ms |
| `linalg:dot` | 119 ms | **138 ms** | **1 ms** |

Nine samples each, printed in full, non-overlapping. The regression is gone and linalg now
matches `vec:` exactly.

| native binary, interpreter | scalar | `--simd` after |
|---|---|---|
| `linalg:add` | 1435 ms | **1 ms** |
| `linalg:dot` | 1605 ms | **0 ms** |

| JVM (GraalVM), steady state, 2000 x 40000 `#f` | scalar | `--simd` |
|---|---|---|
| `linalg:add` | 112-122 ms | 11-33 ms |
| `linalg:dot` | 48-49 ms | 11-12 ms |

(Do not size the JVM win from a short run -- at 60 reps the whole program is JIT warm-up.
The interpreter's win is the *interception*, not the lanes: the defun it replaces is a boxed
`do` loop with a `funcall` of `#'+` per element.)

## The declined-input protocol -- the one structural difference from `vec:`

`vec:` kernels accept packed float arrays and nothing else, so they can simply signal (JVM),
`unreachable` (wasm) or throw (interpreter) on anything else. `linalg:` cannot. Its defuns
also accept:

- general (boxed) arrays -- `(linalg:add #(1 2 3) #(10 20 30))` is ordinary usage,
- **mixed widths** -- NOT an error here (unlike `vec:`): the defun widens both operands and
  keeps the *first* one's width,
- a scalar operand on either side, and two plain numbers,
- mismatched shapes, which it turns into a specific `error` message.

Reproducing all of that in each kernel would duplicate the library. So **every kernel is a
partial function: it answers "declined" for an input it does not handle, and the call site
then runs the scalar `linalg.lisp` defun.** The library stays the single source of truth for
every edge case, error messages included, and nothing is duplicated.

The sentinel is a **null reference / Java `null`** — which works only because compiled nil is
`ACONST_NULL` / a wasm null ref AND none of the intercepted members ever returns nil.
`linalg:array-equal`, which does return nil, is therefore deliberately NOT intercepted (it
also has a per-element `numberp` check). Anything that would need a second sentinel does not
get intercepted.

Two consequences worth remembering:

- **The compilers must evaluate each argument form exactly once, into a temp**, because both
  the kernel and the fallback read it. Recompiling the argument expression for the fallback
  would double its side effects. Pinned by `anArgumentFormIsEvaluatedExactlyOnceEvenWhenThe
  KernelDeclines` (JVM) and `wasmGcSimdLinalgDeclinedInputsRunTheScalarDefun` (wasm).
- **The tree shakers need no new root**: the emitted call site has an ordinary call edge to
  both the kernel and the scalar defun, so the defun stays reachable wherever a kernel can
  decline.

## The intercepted set (15 members)

`add` `sub` `mul` `div` `sum` `norm` `amax` `amin` `argmax` `argmin` `trace` `transpose`
`reshape` `dot` `outer`.

Accelerated **transitively**, so they are not intercepted directly: `mean` (calls `sum`),
`matmul` (calls `dot`), `flatten` (calls `reshape`), `solve` (calls `inv` then `dot`).

**Never** intercepted: `emap` (an arbitrary Lisp callback), `det` / `inv` / `solve`'s
pivoting elimination (data-dependent pivots, sequential column dependency), `array-equal`
(the nil-return sentinel collision), and the constructors.

`#'linalg:dot` still names the scalar defun on the compiled backends -- the interception is
at the *call site* there, while the interpreter overrides the *function binding*. So a
`linalg:` function passed to `funcall` / `mapcar` is not accelerated when compiled. Same
behavior as `vec:`, deliberately.

## What is vectorized, and what is merely de-boxed

Not every member has a lane form worth writing. The interception is still worth it for all
fifteen: it removes the per-element box allocation and the generic numeric dispatch that the
compiled defun pays, and on the interpreter it removes the whole tree-walking loop.

| member | interpreter / JVM | wasm-GC |
|---|---|---|
| `add`/`sub`/`mul`/`div`, array with array | lane loop | `gcMap2` lane loop |
| the same, array with a **double** scalar | lane loop | `gcBroadcastF64` lane loop |
| the same, array with a **single** scalar | scalar loop (see below) | `_v_get`/`_v_set` element loop |
| `sum`, `norm`, `dot` (v.v), `dot` (M.v = GEMV) | lane loop (reuses `VecSimdKernels`) | calls the `vec:` kernels |
| `dot` (v.M), `dot` (M.M) | `ikj` lane loop over the output row | `_v_get`/`_v_set` `ijk` loop |
| `outer` | lane loop over the row | `_v_get`/`_v_set` |
| `amax`/`amin`/`argmax`/`argmin`/`trace` | scalar loop | `_v_get` element loop |
| `transpose` | scalar loop | `_v_get`/`_v_set` |
| `reshape` | `Arrays.copyOf` | whole lane-group copy |

`norm` is **fused**: the oracle spells it `(sqrt (sum (emap square a)))` and allocates an
intermediate array per call; every kernel computes `sqrt(dot(a, a))` instead.

`amax`/`amin` are deliberately scalar. A lane `MAX` reduce is wrong twice over: the last
group's padding lanes are **zero**, so an all-negative array would answer `0`; and a
horizontal fold loses the defun's "first strictly greater wins" tie-break. Pinned by
`amaxAndAminKeepTheOracleStrictComparisonSemantics` and the all-negative case in every
backend's test.

## The precision contract

Element-wise results are **bit-identical** to the scalar `linalg.lisp` oracle at both widths.
Only reductions move, and exactly as todo-106 already specified for `vec:`.

- **`array (+) array` at single width computes natively in `float`, and that is exact.**
  `f64` carries 53 bits and a `float` 24, so `53 >= 2*24 + 2` and the oracle's
  widen-compute-narrow round trip yields the correctly rounded `float` for `+`, `-`, `*` and
  `/` alike. This is the innocuous-double-rounding bound; it is the reason `div` could join
  `add`/`sub`/`mul` in the f32 lane loop.
- **`array (+) scalar` at single width does NOT enjoy that bound** -- the scalar is a full
  `double`. Those kernels compute in `double` and narrow once. On the interpreter and JVM
  they are scalar loops (widening an f32 lane would need `FloatVector.convert(F2D)`, the one
  operation todo-106 removed); on wasm-GC they walk `_v_get` / `_v_set`, which promote and
  demote for free. **`(linalg:mul grad 0.1)` over an `#f` gradient is the common shape** --
  `examples/ml/nn-vec.lisp` is exactly it -- and splatting `(f32) 0.1` into f32 lanes would
  move its printed output. Pinned by
  `aSingleFloatArrayBroadcastAgainstAnInexactScalarStaysBitIdenticalToTheOracle`.
- **Reductions follow todo-106**: an `#f` reduction accumulates in single precision and
  promotes to f64 once, at the value boundary. That covers `sum`, `mean`, `norm`,
  `dot` (v.v) and `dot` (M.v), whose per-row dot is `vec:matvec`'s.
- **The matrix product is exempt, and this is the principle:** you accumulate in the lane
  element type only when the **lanes ARE the reduction axis**. In `ikj` the lanes run across
  the output row (the `j` axis), which carries no summation, so the accumulator's width is
  free -- and the oracle's `double` is both more accurate and free of `convert(F2D)`. So
  `dot` (v.M) and `dot` (M.M) accumulate in `double` at both widths and are bit-identical to
  the oracle. (`JvmSimdVectorTemplate.laMatmulF` keeps a `double[]` accumulator row and
  narrows once per output element.)
- **`trace`, `amax`, `amin`, `argmax`, `argmin`** are bit-identical: they read elements
  widened to `double`, exactly as the defun does.
- **`ikj` is not just faster, it is bit-identical at double width.** The oracle's naive `ijk`
  reads `b[k][j]` with stride `p`, which no lane loop can follow. Rewriting it as `ikj` makes
  `b[k][*]` a contiguous row AND visits `k` in the same increasing order into the same
  accumulator cell -- the oracle's own summation order. No transpose, no scratch buffer, no
  tiling.

### The probe that pins it

`v = #f(4096.0 1.0 ... 1.0)`, 1024 elements. `dot(v,v) = 4096^2 + 1023 = 16778239` exactly;
`4096^2` is `2^24`, where the f32 spacing is 2, so the lane holding it swallows every `1.0`
added to it while the other three fold 256 ones each -> `2^24 + 768 = 16777984`.

| probe | scalar (all backends) | `--simd` (all backends) |
|---|---|---|
| `(round (linalg:dot v v))`, `v[0] = 4096.0` | 16778239 | **16777984** |
| `(round (linalg:sum v))`, `v[0] = 2^24` | 16778239 | **16777984** |
| `(round (* 1024 (linalg:mean v)))`, `v[0] = 2^24` | 16778239 | **16777984** |
| `(round (aref (linalg:dot (reshape v '(1 1024)) v) 0))` -- GEMV | 16778240 | **16777984** |
| `(round (aref (linalg:dot v (reshape v '(1024 1))) 0))` -- **v.M** | 16778240 | 16778240 |
| any of the above at `#d` width | 16778239 | 16778239 |

**Nothing else catches a regression here**: every other `#f` test input stays under `2^24`,
where an f32 accumulator is exact. Pinned three times -- `eval/LinalgSimdTest`,
`codegen/jvm/JvmLinalgSimdAccelCompilerTest` (both
`singleFloatReductionsAccumulateInSinglePrecisionUnderSimd`) and
`WasmLispCompilerIntegrationTest.wasmGcSimdLinalgSingleFloatReductionsAccumulateInSingle
Precision`. The three `--simd` backends print the same seven lines, so they pin each other
as well as the contract.

## `>` disagrees across backends already -- do not "fix" it in a kernel

`amax` / `amin` / `argmax` / `argmin` compare with `>`, and rontolisp's `>` on two floats is
**not the same operation on every backend**:

| | `(> 0.0 -0.0)` | `(linalg:amax #d(-0.0 0.0))` |
|---|---|---|
| interpreter (`Environment.compare` -> `Double.compare`, a total order) | `t` | `0.0` |
| JVM (`_cmp`'s Double path -> `DCMPL`, IEEE) | `nil` | `-0.0` |
| wasm-GC (`f64.gt`, IEEE) | `nil` | `0.0` (prints `-0.0` as `0.0`) |

This is **pre-existing** and has nothing to do with `--simd`. Each kernel therefore
reproduces ITS OWN backend's comparison -- `Double.compare` in `LinalgSimdKernels`, plain
`>` in `JvmSimdVectorTemplate` and `f64.gt` in the wasm kernels -- so every backend's
accelerated `amax` is bit-identical to that backend's own scalar defun. Never put a `-0.0`
case in `ci-spec.yaml`. Worth a separate todo; not this one.

## Per-backend mechanics

### Interpreter

`LinalgSimd.install(globalEnv, evaluator)` runs right after `linalg.lisp` is evaluated in
`LispEvaluator.resolveFunction`'s lazy-load hook, guarded by `this.simd`. Each override
**captures the defun it replaces** and applies it on decline through the new package-private
`LispEvaluator.applyGlobal` seam. Unwrap is zero-copy: `LispDoubleFloatArray(double[] data,
int[] dims)`.

`LinalgSimd.available()` / `install(...)` are the ONLY entry points into
`LinalgSimdKernels`, which is what makes `src/web/java/.../Target_LinalgSimd.java` sufficient
to cut the incubator Vector API out of the browser Web Image. `LinalgSimdKernels` delegates
the shared kernels to `VecSimdKernels` rather than copying them, so `LinalgSimd` never
touches `VecSimdKernels` directly and the substitution stays a two-method affair. **A new
public method on `LinalgSimd` that touches the kernels would break it, and only the Pages
workflow's Web Image build would notice** ([[web-playground-native-image-gotcha]]).
`./mvnw -Pweb compile` is the local check.

`RontoLispCli.interpret` probes `VecSimd.available()` once for both packages: they live in
the same incubator module.

### JVM

**One bridge class** (`JvmSimdVectorTemplate`), so one `_simdInit` and one
`resource-config.json` entry -- adding a second template class would need its own entry, and
the failure would be at RUN time, not build time. `JvmSimdRuntimeBuilder` registers the
fifteen `la*` method refs under **package-prefixed keys** (`"linalg:add"`), because
`vec:add` and `linalg:add` share a member name.

The compiled packed array carries an **in-array header** `[rank, dim..., data...]`,
`off = 1 + rank`. So an element-wise linalg kernel is the `vec:` one at a different offset,
and the fresh result must copy the whole header (`laNewLike`) rather than write `[1, n]`.

`JvmLinalgSimdCompiler.compile` emits:

```
_simdInit(); a = <arg1>; b = <arg2>;          // ASTORE into temps
r = Bridge.laAdd(a, b);
if (r == null) r = linalg$colonadd(a, b);     // ALOAD the same temps
```

The gate is `JvmLispCompiler.programUsesAnyAcceleratedSimdOp`, now scanning both packages.
Note it scans the program AFTER `LinalgLibrary.process` has spliced the defuns, so ANY
linalg program embeds the bridge (the spliced `linalg.lisp` itself contains the accelerated
call sites) -- exactly as any `vec:` program does. `(print (+ 1 2))` does not.

### wasm-GC

Fifteen standalone functions at `WasmLispCompiler.linalgFuncBase()` = `FUNC_VEC_BASE + 15`,
emitted only under `--simd`; `userFuncBase()` now shifts by 30. `WasmLispCompilerTest.simd
AppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks` pins the delta -- it is the
only structural guard that a build WITHOUT `--simd` stays byte-identical to one that never
knew the flag. **Update it, never weaken it.**

They must be standalone because `WasmLispCompiler` declares every extra local of a compiled
defun as one `(ref null eq)` group, so a defun body cannot hold a v128 / f64 / i32 local. The
local declarations go through the shared `WasmVecSimdRuntimeBuilder.withLocals` in its fixed
order -- i32, f64, f32, v128, `(ref null eq)`, `(ref null $v128arr)` -- which all the index
arithmetic assumes. Several generic emit helpers there (`farrayField`, `loadHeader`,
`unboxF64`, `makeFarray`, `boxFloat`, ...) were promoted from `private` to package-private so
the linalg builder reuses them; `WasmVecLoops` gained `gcBroadcastF64` next to `gcScale`.

Kernel structure: a `block` per shape, `br` to the outermost block to decline (the `res`
local defaults to null). `ref.test (ref $farray)` on nil is false, so a nil argument declines
for free. Three things the `vec:` kernels did not have to do:

- **Compare dims, not just counts.** `(2 3)` and `(3 2)` have the same element count but the
  defun errors on them. `emitDimsEqual` walks the two `$hash_buckets` arrays.
- **Copy dims into the result** (`copyDims`) rather than build a rank-1 header. Copied, not
  shared, matching `%la-like`'s fresh `make-array`; the rank is a handful of entries.
- **Restore the last group's padding after a broadcast.** `gcMap2`'s bracket already handles
  it for `add`/`sub`/`mul`, and it is not optional for the rest: over the zero padding
  `0 - s = -s`, `s / 0 = inf`, and `0 / 0 = NaN` (that last one is why `div` between two
  arrays needs it too). A later `sum` over the result would fold the garbage in.
  `gcSaveLastGroup` / `gcRestoreLastGroupTail` do it once per call.

`f32x4.div` (`0xFD 0xE7`) was added to `am.ik.wasm.Instruction`, to `WasmVecLoops.f32x4Of`
and to `WasmTreeShaker.skipSimd` (which throws on an unknown `0xFD` sub-opcode, by design).

`reshape` parses its shape designator in wasm: an i31, or a proper cons list of non-negative
i31s. Anything else declines. `flatten` rides on it.

## Verification

- `eval/LinalgSimdTest` (22) -- interception guard (`#'linalg:add` is `#<function
  linalg:add>` under `--simd`, `#<lambda>` without; `emap`/`inv`/`det`/`solve`/`array-equal`/
  `mean`/`matmul`/`flatten` stay `#<lambda>`), byte-identity vs the oracle at both widths and
  both ranks, scalar broadcast on both sides, the declined inputs, the f32 probe.
- `codegen/jvm/JvmLinalgSimdAccelCompilerTest` (14) -- the bridge-embedded dead-flag guard,
  the same byte-identity set, the evaluate-once guard, the library errors still signalling.
- `codegen/wasm/WasmLispCompilerIntegrationTest` (Docker + wasmtime), five cases:
  `wasmGcSimdLinalg{ElementWiseAndShapeKernels,ReductionsAndProducts}AreByteIdenticalToThe
  ScalarPath`, `...DeclinedInputsRunTheScalarDefun`,
  `...SingleFloatReductionsAccumulateInSinglePrecision`, `...ComposesWithOptimize`.
- `WasmLispCompilerTest.simdAppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks`.
- `ci-spec.yaml` never passes `--simd`, so the cross-backend E2E is unaffected. The component
  leg (`--component --simd`) and `--optimize` were verified by hand and by the integration
  test.
- `examples/ml/{tiny-llm,linear-regression,heat3d,deep-digits}.lisp` print byte-identical
  output with and without `--simd` on all three backends. (`nn-vec.lisp` has a random init,
  so only its headings are stable -- `examples.yaml` checks only those.)

## Not done

- wasm-GC `dot` (v.M / M.M), `outer` and `transpose` are element loops, not lane loops. A
  lane form needs the `i8x16.shuffle` row window `WasmVecSimdRuntimeBuilder.emitRowGroup`
  already has, plus a lane-aligned scratch vblock to accumulate a row into. Worth doing if a
  wasm GEMM ever gets hot.
- A linalg program dominated by `emap` / `inv` / `transpose` still pays `_v_get`/`_v_set` on
  wasm-GC and stays slower under `--simd` than without it. That penalty is **intrinsic** to
  wasm-GC `--simd`: a `v128` can only be read out of an `(array (mut v128))`, never out of an
  `(array (mut f32))`, so no representation is fast for both lane loops and scalar element
  access. `--no-gc --simd` is the escape hatch, and it cannot run `linalg:` at all.
  A possible follow-up nobody has costed: keep `(array (mut f64))` under `--simd` and gather
  lanes with 2 (f64) or 4 (f32) `array.get` + `replace_lane` per group. That would delete the
  representation switch -- and with it every un-intercepted regression -- at maybe 2-4x the
  kernel cost. It contradicts todo-105's choice; measure before believing either.
- A possible `emap` special case when `f` is a *known builtin* (`#'abs`, `#'sqrt`, `#'exp`).
  `linalg:emap #'silu` in `examples/ml/tiny-llm.lisp` is a user lambda and would not benefit.
