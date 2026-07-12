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
| wasm-GC (`-o prog.wasm --simd`) | `codegen/wasm/WasmLinalgSimdCompiler` (call site) | `WasmLinalgSimdRuntimeBuilder` (30 emitted functions) |

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
- arrays of DIFFERENT shapes: since 2026-07-12 the defun broadcasts them by the numpy
  rules (`%la-bcast-loop`, see `.kb/linalg.md`) and signals the specific shape-mismatch
  `error` only when no broadcast fits. Every element-wise kernel already declined an
  unequal-dims pair, so the kernels needed NO change -- a broadcast pair simply falls
  back to the defun (an optimization opportunity, not a correctness issue; a lane form
  for the common matrix-row broadcast could be added later behind the same decline).

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

## The intercepted set (32 members)

`add` `sub` `mul` `div` `sum` `norm` `amax` `amin` `argmax` `argmin` `trace` `transpose`
`reshape` `dot` `outer`, plus the todo-109 unary ufuncs `exp` `log` `tanh` `sin` `cos`
`tan` `asin` `acos` `atan` `sinh` `cosh` `sqrt` `abs` `negative` `sign` (named emaps; see
the todo-109 section of `.kb/vec.md` for the per-backend lane vs element-loop decisions
and the wasm defun-edge mirroring; `log`/`tanh`, then `sin`/`cos`/`tan`, then
`asin`/`acos`/`atan`/`sinh`/`cosh` are the Phase 2 members over the new WASM software
scalar builtins), plus the todo-109 Phase 3 comparison selects `maximum` `minimum`
(strict `(if (> x y) x y)` / `(if (< x y) x y)` selects, never Math.max or a lane
min/max -- the SECOND operand wins any false comparison, ties and NaN included, and
unlike the transcendentals the values are CROSS-BACKEND-identical; see the Phase 3
section of `.kb/vec.md`).

Accelerated **transitively**, so they are not intercepted directly: `mean` (calls `sum`),
`matmul` (calls `dot`), `flatten` (calls `reshape`), `solve` (calls `inv` then `dot`),
`square` (calls `mul`), `reciprocal` (calls `div`), `clip` (calls `maximum` then
`minimum`), `relu` (calls `maximum` with the 0.0 bound).

**Never** intercepted: `emap` (an arbitrary Lisp callback), `det` / `inv` / `solve`'s
pivoting elimination (data-dependent pivots, sequential column dependency), `array-equal`
(the nil-return sentinel collision), and the constructors.

`#'linalg:dot` still names the scalar defun on the compiled backends -- the interception is
at the *call site* there, while the interpreter overrides the *function binding*. So a
`linalg:` function passed to `funcall` / `mapcar` is not accelerated when compiled. Same
behavior as `vec:`, deliberately.

## What is vectorized, and what is merely de-boxed

Not every member has a lane form worth writing. The interception is still worth it for all
thirty-two: it removes the per-element box allocation and the generic numeric dispatch that the
compiled defun pays, and on the interpreter it removes the whole tree-walking loop.

| member | interpreter / JVM | wasm-GC |
|---|---|---|
| `add`/`sub`/`mul`/`div`, array with array | lane loop | `gcMap2` lane loop |
| the same, array with a **double** scalar | lane loop | `gcBroadcastF64` lane loop |
| the same, array with a **single** scalar | scalar loop (see below) | `_v_get`/`_v_set` element loop |
| `sum`, `norm`, `dot` (v.v), `dot` (M.v = GEMV) | lane loop (reuses `VecSimdKernels`) | calls the `vec:` kernels |
| `dot` (v.M), `dot` (M.M) | `ikj` lane loop over the output row | `ikj` lane loop: shuffle-window b rows into an f64 scratch row, `_v_set` write-out (see below) |
| `outer` | lane loop over the row | whole destination groups when `m % lanes == 0`, else `_v_get`/`_v_set` |
| `amax`/`amin`/`argmax`/`argmin`/`trace` | scalar loop | `_v_get` element loop |
| `sqrt`/`abs`/`negative` (unary, todo 109) | lane loop | `gcMap1` lane loop (defun-mirroring forms) |
| `maximum`/`minimum`, array with array | scalar select loop (perf-only choice; bits identical either way) | `gcMap2Select` gt/lt mask + bitselect lane loop, BOTH widths |
| the same, array with a **double** scalar | scalar select loop | `gcBroadcastSelectF64` lane select (save/restore bracket kept: a select over padding can answer s) |
| the same, array with a **single** scalar | scalar loop widened vs the full double | `_v_get`/`_v_set` element loop widened vs the full double |
| `exp`/`log`/`tanh`/`sin`/`cos`/`tan`/`asin`/`acos`/`atan`/`sinh`/`cosh`/`sign` (unary, todo 109) | de-boxed scalar loop (the same `java.lang.Math` call) | `_v_get`/`_v_set` element loop emitting the defun's f64 sequence |
| `transpose` | scalar loop | lanes x lanes register-block shuffles when BOTH dims are lane-aligned, else `_v_get`/`_v_set` |
| `reshape` | `Arrays.copyOf` | whole lane-group copy |

The wasm-GC lane forms for `dot` (v.M / M.M) / `outer` / `transpose` shipped as the todo-107
follow-up (2026-07-10). The GEMM loop reads each `b` row through the same `i8x16.shuffle`
window as `vec:matvec` (`WasmVecSimdRuntimeBuilder.emitRowGroup`, promoted package-private)
and multiply-accumulates whole groups into a lane-aligned **f64 scratch row** (`_v_new(p, 0)`,
reused and re-zeroed per output row), written out element-wise through `_v_set` -- O(n·p)
writes against O(n·m·p) flops. At `#f` width each f32x4 window group is widened exactly via
the new `f64x2.promote_low_f32x4` (`am.ik.wasm.Instruction` 0xFD 0x5F, also added to
`WasmTreeShaker.skipSimd`), low half directly and high half swapped down by a shuffle; when
`p % 4` is 1 or 2 the high half's accumulator index reaches the scratch row's sentinel group,
which exists and is never read back. The window's overhang past a row's end reads REAL
next-row elements (unlike matvec's zero-padded x) but lands only in accumulator lanes past
`p`, which the write-out never reads. `transpose` uses two shuffles per 2x2 f64 block and the
classic eight-shuffle butterfly per 4x4 f32 block. No new function indices: the same 15
linalg kernels got new bodies, so `userFuncBase()` and every structural pin are untouched.

Measured (M4, wasmtime 46, 9 samples each, non-overlapping; `#f` unless noted):

| wasm-GC | scalar | `--simd` element loop (todo-107) | `--simd` lane form |
|---|---|---|---|
| `dot` M.M 256x256 (1 rep) | 924-989 ms | 107-119 ms | **8.7-8.9 ms** |
| `dot` M.M 256x256 `#d` | 1080-1159 ms | 107-110 ms | **11.4-11.9 ms** |
| `dot` v.M 1024 . 1024x1024 (10 reps) | 2675-2801 ms | 69-76 ms | **5.3-5.5 ms** |
| `outer` 1024x1024 (10 reps) | 628-916 ms | 76-89 ms | **3.8-4.8 ms** |
| `transpose` 1024x1024 (10 reps) | 475-632 ms | 79-82 ms | **8.0-9.5 ms** |

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

## `>` agrees across backends since todo-108 -- the kernels all compare IEEE

`amax` / `amin` / `argmax` / `argmin` compare with `>`. Historically rontolisp's `>` on
two floats was three different operations (interpreter `Double.compare`, a total order;
JVM `DCMPL` for every operator; wasm literal `f64.gt` but a signum `_rat_cmp` through
variables), so each kernel mirrored ITS OWN backend and `(linalg:amax #d(-0.0 0.0))`
differed per backend. **`.todo/108` fixed the scalar comparisons on all three backends**
(interpreter `compareNumeric` gained an UNORDERED state; the JVM literal path picks
DCMPG/DCMPL per operator and the runtime path uses the `_cmpb` bitmask; wasm's variable
path funnels through `_rat_cmp_bits`), and `LinalgSimdKernels` switched from
`Double.compare` to plain Java `>`/`<` in the same change -- the lockstep the old version
of this section demanded. All three kernels now compare IEEE, all three match their own
scalar defun (pinned by `LinalgSimdTest`'s `-0.0` oracle-match cases), and the defuns
match each other: `(linalg:amax #d(-0.0 0.0))` is `-0.0` everywhere (first-element tie
win, since IEEE `>` is false on a `0.0`/`-0.0` tie). `-0.0` / NaN comparison cases are
allowed in `ci-spec.yaml` now; the float-edge cases there pin the convergence.

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
twenty `la*` method refs under **package-prefixed keys** (`"linalg:add"`), because
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

Thirty standalone functions at `WasmLispCompiler.linalgFuncBase()` = `FUNC_VEC_BASE
+ 47` (the vec: block grew to 47 with the todo-109 unary kernels), emitted only under
`--simd`; `userFuncBase()` now shifts by 77. `WasmLispCompilerTest.simd
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
- `codegen/wasm/WasmLispCompilerIntegrationTest` (Docker + wasmtime), six cases:
  `wasmGcSimdLinalg{ElementWiseAndShapeKernels,ReductionsAndProducts}AreByteIdenticalToThe
  ScalarPath`, `...LaneProductsMatchTheScalarPathAtEveryRowLaneOffset` (the GEMM / outer /
  transpose lane forms: every shuffle-offset variant via a 7-column `#f` matrix, the odd-`p`
  sentinel-group write, aligned vs unaligned outer/transpose, a next-row inf inside the
  window overhang), `...DeclinedInputsRunTheScalarDefun`,
  `...SingleFloatReductionsAccumulateInSinglePrecision`, `...ComposesWithOptimize`.
- `WasmLispCompilerTest.simdAppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks`.
- `ci-spec.yaml` never passes `--simd`, so the cross-backend E2E is unaffected. The component
  leg (`--component --simd`) and `--optimize` were verified by hand and by the integration
  test.
- `examples/ml/{tiny-llm,linear-regression,heat3d,deep-digits}.lisp` print byte-identical
  output with and without `--simd` on all three backends. (`nn-vec.lisp` has a random init,
  so only its headings are stable -- `examples.yaml` checks only those.)

## Not done

- The lane-unaligned `outer` (`m % lanes != 0`) and `transpose` (either dim unaligned)
  shapes keep the `_v_get`/`_v_set` element loop, as do `amax`/`amin`/`argmax`/`argmin`/
  `trace` and the single-float scalar broadcast. All still several times faster than the
  defun; a blended-edge lane form was judged not worth the shuffle bookkeeping.
- A linalg program dominated by `emap` / `inv` still pays `_v_get`/`_v_set` on
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
