# `linalg:` kernel interception (`--simd`)

Read [[vec]] first: this is "do what `vec:` does, for `linalg:`", reusing the `vec:` lane loops
rather than copying them. [[linalg]] has the semantics of the library accelerated. Keep the
intercepted set, the declined-input fallback and the precision contract in sync with
`doc/{en,ja}/guides/simd-acceleration.md`.

| backend | interceptor | kernels | without `jdk.incubator.vector` |
|---|---|---|---|
| interpreter | `eval/LinalgSimd` (re-`defineFunction`) | `eval/LinalgSimdKernels` | `VecSimd.available()` probes first; `RontoLispCli.enableSimd` warns once and leaves `evaluator.setSimd` off |
| JVM | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmSimdVectorTemplate.la*` (one embedded bridge) | `_simdInit` catches the `LinkageError` from `Lookup.defineClass`, warns once; every call site checks `_simdReady()` BEFORE resolving a reference into the bridge |
| wasm-GC | `codegen/wasm/WasmLinalgSimdCompiler` (call site) | `WasmLinalgSimdRuntimeBuilder` (56 emitted functions) | n/a |

`--no-gc` is out of scope: `linalg:` cannot compile there at all.

## The flags stacked on this seam
- **`--blas`** ([[linalg-blas]]) and **`--gpu`** ([[gpu]]) are a SECOND and THIRD flag over the
  same seam, interpreter + JVM only. `--gpu` also takes `%la-matmul-nd`, the twelve element-wise
  members whose scalar cost is a libm call, and the ten members whose CPU kernel BELOW is a scalar
  odometer walk rather than a lane loop (`add` `sub` `mul` `div` `maximum` `minimum` at a BROADCAST
  shape, `sum` `amax` `amin` with `:axis`, `transpose` with an axes list). **That last set is chosen
  against the CPU column in THIS file.** Hence the JVM interceptor is `JvmLinalgKernelCompiler`, not
  `JvmLinalgSimdCompiler` -- it emits a CHAIN of up to three attempts over one set of temps, ending
  at the scalar defun, at the EXTENDED (option-form) call sites too. With `--blas`/`--gpu` off a
  `--simd` build is unchanged.
- **`--parallel`** ([[simd-parallel]]) is a modifier, not a rung: the matrix products run a row
  range per thread and stay bit-identical.
- **`--gpu` is the one flag whose element-wise results are NOT bit-identical to the defun** -- for
  its TRANSCENDENTAL tier only; its strided tier is bit-identical at both widths. A `--gpu` run is
  no longer byte-comparable with a `--simd` one.

## Why: `--simd` used to make `linalg:` SLOWER on wasm-GC
`--simd` switches the packed float-array representation to a `TYPE_VBLOCK` over an
`(array (mut v128))`, so every *scalar* element read/write goes through `_v_get`/`_v_set`. `vec:`
kernels never pay it; no `linalg:` function was intercepted, so linalg paid the new representation
and got nothing back (~10% slower). The interpreter's win is the INTERCEPTION, not the lanes.

## The declined-input protocol -- the one structural difference from `vec:`
`linalg:` defuns also accept boxed arrays, **mixed widths** (NOT an error: the defun widens both and
keeps the FIRST one's width), a scalar on either side, two plain numbers, and different shapes
(numpy broadcast). A SAME-width broadcast pair IS handled by the kernels; a MIXED-width or
incompatible pair declines.

**Every kernel is a partial function: it answers "declined" for an input it does not handle, and the
call site then runs the scalar `linalg.lisp` defun.** The library stays the single source of truth
for every edge case, error messages included.

The sentinel is a **null reference / Java `null`**, which works only because compiled nil is
`ACONST_NULL` / a wasm null ref AND none of the intercepted members ever returns nil.
`linalg:array-equal`, which does return nil, is therefore deliberately NOT intercepted. **Anything
that would need a second sentinel does not get intercepted.**
- **The compilers must evaluate each argument form exactly once, into a temp**, because both the
  kernel and the fallback read it (`anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines`,
  `wasmGcSimdLinalgDeclinedInputsRunTheScalarDefun`).
- The tree shakers need no new root: the call site has ordinary call edges to both.
- **An intercepted member may be VARIADIC**, and six have EXTENDED (option-form) call sites:
  `transpose` with a positional axes list, `sum`/`amax`/`amin` with `:axis`/`:keepdims`,
  `argmax`/`argmin` with `:axis`. Kernels stay POSITIONAL; `compiler.LinalgKernelCallLayout.layout`
  -- shared by both codegens so they pattern-match identically -- maps argument forms onto kernel
  parameters (literal `:keyword value` pairs over the declared keywords, each at most once, any
  order, missing padded with null). Anything else routes to the ordinary direct-call path, where the
  defun's `&key` prologue signals. Decline branches package the rest list from the SAME temps,
  keyword literals included; both compilers verify the defun's required count still matches and bail
  to `compileDefault` otherwise. The interpreter needs the arity-RANGE guard in `LinalgSimd.define`
  (1..5 for sum/amax/amin, 1..3 for argmax/argmin) plus `LinalgSimd.options`, the runtime twin.

## The intercepted set (49 members)
`add` `sub` `mul` `div` `sum` `norm` `amax` `amin` `argmax` `argmin` `trace` `transpose` `reshape`
`dot` `outer`; the unary ufuncs `exp` `log` `tanh` `sin` `cos` `tan` `asin` `acos` `atan` `sinh`
`cosh` `sqrt` `abs` `negative` `sign`; the comparison selects `maximum` `minimum` (strict
`(if (> x y) x y)` selects, never Math.max or a lane min/max -- the SECOND operand wins any false
comparison, ties and NaN included, and these are CROSS-BACKEND-identical); the masks `greater`
`greater-equal` `less` `less-equal` `equal`, `where` (3), `take-rows` (2); and the INTERNAL members
`%la-im2col` (5) / `%la-col2im` (6), `%la-matmul-nd` (2), `erf` (1), `%la-rng-fill` (5),
`%la-adam-step` (5), `%la-gather-strided` (5), `%la-scatter-rows` (3), `%la-sum-squares` (2),
`%la-scale` (2).

A `%`-prefixed member's canonical qualified spelling carries the DOUBLE colon
(`linalg::%la-im2col`), which is how the interpreter's function binding, the compilers'
`ctx.functions` keys and the emit-gate symbol scan must compose it
(`Jvm`/`WasmLinalgSimdCompiler.qualifiedName`, `LinalgSimd.define` branch on the `%` prefix).

Accelerated **transitively**: `rand`/`randn`/`uniform`, `torch:step` over `adam`/`adamw`, `mean`,
`matmul`, `flatten`, `solve`, `square`, `reciprocal`, `clip`, `relu`. **Never** intercepted: `emap`
(arbitrary callback), `det`/`inv`/`solve`'s pivoting elimination (data-dependent pivots),
`array-equal` (sentinel collision), the constructors.

`#'linalg:dot` still names the scalar defun on the compiled backends -- the interception is at the
*call site* there, while the interpreter overrides the *function binding*. So a `linalg:` function
passed to `funcall`/`mapcar` is not accelerated when compiled. Same as `vec:`.

### Per-member notes
- **`%la-im2col`/`%la-col2im`** are pure index arithmetic, bit-identical at both widths; rank-4
  packed operands with i31 window parameters only.
- **The declined-shape follow-up** closed the three shapes still running boxed defuns: the general
  numpy BROADCAST between two same-width packed arrays, `transpose`'s rank-n AXES form, and the AXIS
  forms of `sum`/`amax`/`amin`/`argmax`/`argmin` (+7 wasm functions, `BCAST`, `TRANSPOSE_AXES`,
  `SUM_AXIS`, `AMAX_AXIS`, `AMIN_AXIS`, `ARGMAX_AXIS`, `ARGMIN_AXIS`). All are deliberately SCALAR
  walks: the win is de-boxing, and scalar double arithmetic is what makes them bit-identical.
  **Being scalar walks is also what made them `--gpu`'s phase-3 member set**; a lane form for the
  row-broadcast case would move that member's crossover, so rerun the GPU item's
  `shaped-baseline.lisp` / `StridedCrossover.java` first.
- **`%la-matmul-nd`** (`torch.bmm`): **the kernel is `dot`'s M.M lane loop, once per batch offset**,
  not a second lane loop -- all three backends factored the rank-2 product into an offset-taking slab
  (`LinalgSimdKernels.matmulInto`, `JvmSimdVectorTemplate.laMatmulInto`, wasm's `emitGemmRow`).
  Offsets come from the `%la-batch-strides` odometer = `%la-bcast-strides` scaled by the trailing
  matrix size, so a BROADCAST leading axis is stride 0 and needs no special case. **Precision is a
  per-batch `linalg:dot`, not the defun.** `--gpu` intercepts it too, on the TOTAL work
  `batch*n*m*p`; a broadcast axis UNDER a non-broadcast one declines there and lands back here.
  `#f` is now the FASTER width on this shape everywhere.
- **`erf`** is `(linalg:emap #'%la-erf-1 a)` and `emap` is never intercepted, so it got nothing from
  the flag while `torch:gelu`'s DEFAULT (`:approximate :none`) is built on it. **The kernel is
  `%la-erf-1`'s own arithmetic in the defun's order, bit-identical at both widths** -- the A&S 7.1.6
  all-positive-term series in DOUBLE at both widths, narrowing only on the store. `exp` is
  per-backend, so `(linalg:erf #d(-1.0))` differs in the last two digits on wasm. **No lane form,
  and the reason is not "no v128 instruction"**: the per-element iteration count is DATA-DEPENDENT.
  Measured on aarch64/NEON, a lane `exp` (`VectorOperators.EXP`) is 14x SLOWER than the scalar
  de-boxed loop AND not bit-identical to `Math.exp`, while INTERCEPTION alone buys 132x on the
  interpreter and 13x on wasm-GC. **Do not spend time on a masked lane form**; if revisited, measure
  on a machine with 4+ f64 lanes first.
- `abs` and unary minus USED to be per-backend -- with no double literal among their argument forms
  they took wasm's generic path (`abs` via `_rat_cmp`, leaving `-0.0` alone where `Math.abs` folds it
  to `0.0`). Both wasm emitters now take `f64.abs`/`f64.neg` on the branch that has established a
  float operand, and the kernel is spelled the same way.

### The optimizer update and the generator (`%la-adam-step` / `%la-rng-fill`)
Both are here because **the seam intercepts `linalg:` names and nothing else**, and a JFR profile of
`train-gpt-soseki.lisp` under `--gpu --simd` said the remaining cost was two boxed Lisp loops that
were not `linalg:` at all (`torch::%o-adam-step` 22-31%, `linalg:rand`/`randn` 16%). **So the loops
moved to `linalg:` rather than the seam widening to `torch:`.** `torch::%o-sgd-step` was deliberately
NOT moved: same shape, but on no profile.
- **`%la-adam-step (x g m v ps)`** carries the whole rule in an eleven-element packed double vector
  (`lr`, `lr*wd`, `wd`, `b1`, `1-b1`, `b2`, `1-b2`, `eps`, `c1`, `c2`, `mode`). **`mode` replaced two
  booleans**: 0 = no weight decay, 1 = COUPLED (`torch.optim.Adam`), 2 = DECOUPLED (`AdamW`), and
  `wd = 0` collapses both spellings, so three values cover four combinations. **`lr * wd` is
  multiplied by the CALLER** while both may still be exact rationals -- storing them separately would
  round twice and move a run with `:lr 1/100`. Bit-identical at both widths. **The kernel mutates in
  place, so every check is up front**: no mid-loop decline, and a declined call must have written
  nothing.
- **`%la-rng-fill (out st mode lo span)`** is the one fill loop `rand`/`randn`/`uniform` share. The
  generator's state lives in three SPECIALS a kernel cannot read, so passing it in as an ARRAY and
  answering the state it ends on as one makes the fill a pure function
  (`%la-rng-state`/`%la-rng-restore` bracket it). The rule does not move: the DEFUN still calls
  `%la-rng-next` in a loop, so there is exactly one copy. `mode` picks the element rule (0 uniform,
  1 Irwin-Hall, 2 `lo + span * draw`). **The closed form (`s_k = a^k * s mod m`) was not needed** --
  what the defun paid was a boxed double per draw, not the sequencing; keep it for a device kernel.
  It declines a state vector outside the exact-non-negative-integers-below-`2^23` range, which is
  where `a * s` cannot overflow an `int` and Java `%` / wasm `i32.rem_s` agree with Lisp `mod`.
  **`linalg:seed`'s promise makes byte identity non-negotiable.**
- 2.2x per training step on a `--gpu --simd` build, 1.05x on a CPU-only one. **Do not size the JVM
  gain from a micro-benchmark**: with ONE set of arrays escape analysis scalarizes the boxing away,
  which is why the profile found 22% where a micro-benchmark suggests 2%.

### The selects and copies (eleven members)
A JFR profile said 40% of what was left of a training step was boxed `row-major-aref` walks --
`where` through `torch:masked-fill` (22%), `greater` through the dropout mask, `slice` through
`torch:cat`'s adjoint, `torch:index-select`'s scatter-add, `torch:clip-grad-norm`'s two loops,
`take-rows`. None is arithmetic, so every one is **bit-identical to its defun at both widths by
construction**.
- **The five comparison masks** ride the element-wise dispatch (op codes `BOP_GT`..`BOP_EQ` = 6..10),
  a 1.0/0.0 result at the first ARRAY operand's width, and the defun's own IEEE comparisons on
  widened elements -- so `-0.0` equals `0.0` and NaN compares false everywhere. NOT symmetric, so the
  scalar-on-the-left shape runs the reversed loop.
- **`where` (3)** is a three-operand broadcast walk, result at `x`'s width when `x` is an array, else
  `y`'s, else double. A kernel, not a materialization: the defun builds three broadcast copies first.
- **`%la-gather-strided (a od rs base single)`** -- the FIFTH PARAMETER CHANGED from the element-type
  symbol to a flag (nil = double), which is what made it interceptable on all three backends without
  a symbol comparison (a quoted symbol is a `String` on the JVM, an interned `$string` offset on
  wasm, a `LispSymbol` on the interpreter). It **computes the walk's lowest and highest flat index up
  front**, declining when either leaves `a` -- so a declined call has read nothing.
- **`take-rows` / `%la-scatter-rows (z g idx)`** read the index vector exactly as the defun
  (`(truncate (aref idx i))`); the range check is `v > -1.0 && v < rows`, so `-0.5` truncates to 0
  like the defun and NaN declines. `%la-scatter-rows` is NEW in `linalg.lisp`
  (`torch:index-select`'s backward, moved under a `linalg:` name so the seam can reach it).
- **`%la-sum-squares (g acc)` / `%la-scale (g s)`** are `torch:clip-grad-norm`'s two loops:
  `(+ total (* v v))` as a LEFT fold in double from the caller's accumulator (byte identity, NOT a
  lane reduction -- a `linalg:sum` of squares would follow the reduction contract and move the clip
  scale), and `(* g s)` in place. A ratio accumulator or scale declines.

Touch points: `LinalgSimd`/`LinalgSimdKernels` (`bcastStrides` went package-private for `where`);
`JvmLinalgKernelCompiler.KERNELS` + `JvmSimdVectorTemplate`; `WasmLinalgSimdCompiler` +
`WasmLinalgSimdRuntimeBuilder` (`COMPARE_GT`..`COMPARE_EQ` 45..49, `WHERE` 50, `GATHER_STRIDED` 51,
`TAKE_ROWS` 52, `SCATTER_ROWS` 53, `SUM_SQUARES` 54, `SCALE` 55; `FUNC_COUNT` 45 -> 56). The wasm
builder grew `emitOdometerN` and an `emitBcastShape` overload with an explicit decline depth (the
original hard-coded `br_if 2`, only right at the exit block's top level; `where` needs 4).

## What is vectorized, and what is merely de-boxed
Interception is worth it for all forty-nine even without lanes: it removes the per-element box
allocation and the generic numeric dispatch, and on the interpreter the whole tree-walking loop.
- **Lane loops in both columns**: `add`/`sub`/`mul`/`div` array-with-array and with a **double**
  scalar (`gcMap2` / `gcBroadcastF64`); `sum`, `norm`, `dot` (v.v), `dot` (M.v = GEMV) reusing the
  `vec:` kernels; `dot` (v.M / M.M) as an `ikj` loop; `outer`; `sqrt`/`abs`/`negative` (`gcMap1`);
  `reshape`. `maximum`/`minimum` are a scalar select loop on interpreter/JVM (perf-only choice, bits
  identical either way) and `gcMap2Select` mask+bitselect on wasm at BOTH widths. `transpose` uses
  lanes x lanes register-block shuffles when BOTH dims are lane-aligned.
- **Scalar / element loops**: a **single**-float scalar broadcast; `amax`/`amin`/`argmax`/`argmin`/
  `trace`; the transcendentals and `sign`; lane-unaligned `transpose`; every declined-shape helper
  (`BCAST`, `TRANSPOSE_AXES`, the `*_AXIS` folds); `erf`, `%la-rng-fill`, `%la-adam-step`, the
  selects and copies.

The wasm-GC lane forms for `dot` (v.M / M.M) / `outer` / `transpose` read each `b` row through the
same `i8x16.shuffle` window as `vec:matvec` (`WasmVecSimdRuntimeBuilder.emitRowGroup`, promoted
package-private) and multiply-accumulate whole groups into a **scratch row of the operand width**
(`_v_new(p, kind)`, re-zeroed per output row), written out through `_v_set` -- O(n·p) writes against
O(n·m·p) flops. One loop serves both widths. The window's overhang past a row's end reads REAL
next-row elements but lands only in accumulator lanes past `p`, which the write-out never reads.
**An `#f` scratch row is what makes the matrix product a reduction-contract kernel rather than a
bit-identical one**, and all three backends had to move together; `f64x2.promote_low_f32x4` is no
longer emitted (its `WasmSections.skipSimd` case stays, harmless).

`norm` is **fused**: the oracle spells it `(sqrt (sum (emap square a)))` and allocates an
intermediate per call; every kernel computes `sqrt(dot(a, a))`.

`amax`/`amin` are deliberately scalar. A lane `MAX` reduce is wrong twice over: the last group's
padding lanes are **zero**, so an all-negative array would answer `0`; and a horizontal fold loses
the defun's "first strictly greater wins" tie-break
(`amaxAndAminKeepTheOracleStrictComparisonSemantics`, plus the all-negative case in every backend).

## The precision contract
Element-wise results are **bit-identical** to the scalar oracle at both widths. Only reductions move.
- **`array (+) array` at single width computes natively in `float`, and that is exact**: `53 >= 2*24
  + 2`, so the widen-compute-narrow round trip yields the correctly rounded `float` for `+ - * /`
  alike (the innocuous-double-rounding bound; it is why `div` could join the f32 lane loop).
- **`array (+) scalar` at single width does NOT enjoy that bound** -- the scalar is a full `double`,
  so those kernels compute in `double` and narrow once. **`(linalg:mul grad 0.1)` over an `#f`
  gradient is the common shape**; splatting `(f32) 0.1` into f32 lanes would move its printed output
  (`aSingleFloatArrayBroadcastAgainstAnInexactScalarStaysBitIdenticalToTheOracle`).
- **Reductions**: an `#f` reduction accumulates in single precision and promotes to f64 once, at the
  value boundary -- `sum`, `mean`, `norm`, `dot` (v.v), `dot` (M.v).
- **The matrix product follows the reduction contract too**: `dot` (v.M) and (M.M) accumulate in the
  OPERAND width, folding each cell over `k` in the oracle's ascending order. The defun cannot follow
  -- rontolisp has one float type and it is f64 -- so there is no version where oracle and kernel
  agree. Worst case ~3-4% RELATIVE error, always on a cell whose true value cancelled to near zero;
  that is what every f32 GEMM does, PyTorch's CPU `sgemm` included. **Which lanes ran cannot move the
  answer**: the lanes go across the output row (`j`), which carries no summation, so all three
  `--simd` backends agree bit for bit. At `#d` nothing changed.
- **Do not restore the f64 accumulator, which WAS bit-identical**: it forbids lanes (an f64
  accumulator can only be fed through `FloatVector.convert(F2D)`, which on NEON has no intrinsic at
  all and left `#f` matmul 2x slower than `#d`). `MatmulFProbe.java` in the GPU item's directory
  measures all four candidate kernels and is rerunnable; **it answers differently per architecture,
  so rerun before quoting**. What holds on BOTH is the ranking: f32 lanes with an f32 accumulator
  wins. Do not spend time on `convertShape` variants either.
- **`trace`, `amax`, `amin`, `argmax`, `argmin`, `erf`** and all the declined-shape kernels are
  bit-identical (elements read widened to `double`, exactly as the defun).
- **The AXIS folds do NOT follow the lane-reduction contract**: `(linalg:sum a :axis 0)` accumulates
  in f64 from the defun's `0` seed in the defun's order, and the `amax`/`amin` folds mirror
  `(if (> x acc) x acc)` -- the ACCUMULATOR wins ties/NaN, the opposite of the element-wise select,
  so `(linalg:amax #d((-0.0 0.0)) :axis 1)` is `#d(-0.0)` while `(linalg:maximum #d(-0.0) #d(0.0))`
  is `#d(0.0)`. A vector reduced without keepdims returns the boxed f64 accumulator itself (never
  narrowed, even for `#f`), and the axis `argmax`/`argmin` results are packed DOUBLE arrays at any
  input width -- exactly as the defuns answer.
- **`ikj` is not just faster, it is bit-identical at double width**: it makes `b[k][*]` contiguous
  AND visits `k` in the same increasing order into the same accumulator cell as the oracle's `ijk`.
  No transpose, no tiling.

### The probe that pins it
`v = #f(4096.0 1.0 ... 1.0)`, 1024 elements. `dot(v,v) = 4096^2 + 1023 = 16778239` exactly; `4096^2`
is `2^24`, where the f32 spacing is 2, so the lane holding it swallows every `1.0` added to it while
the other three fold 256 ones each -> `2^24 + 768 = 16777984`.

| probe | scalar | `--simd` |
|---|---|---|
| `(round (linalg:dot v v))` / `(round (linalg:sum v))` / `(round (* 1024 (linalg:mean v)))` | 16778239 | **16777984** |
| GEMV `(round (aref (linalg:dot (reshape v '(1 1024)) v) 0))` | 16778240 | **16778176** |
| v.M `(round (aref (linalg:dot v (reshape v '(1024 1))) 0))`, and the rank-3 `matmul` | 16778240 | **16777216** |
| any of the above at `#d` | 16778239 | 16778239 |

**The GEMV row moves with `vec:matvec`'s**: `linalg`'s matrix-by-vector case is NOT a kernel of its
own (`LinalgSimdKernels.matvecF` delegates to `VecSimdKernels.matvecF`; the wasm builders route via
the `vec:` kernel), so when that row gained four independent accumulators above 32 columns this probe
moved to `2^24 + 960`. **Nothing else catches a regression here**: every other `#f` test input stays
under `2^24`, where an f32 accumulator is exact. Pinned three times -- `LinalgSimdTest` and
`JvmLinalgSimdAccelCompilerTest` (`singleFloatReductionsAccumulateInSinglePrecisionUnderSimd`),
`WasmLispCompilerIntegrationTest.wasmGcSimdLinalgSingleFloatReductionsAccumulateInSinglePrecision`.

## Float comparison semantics: `>` and the signed-zero surface
`amax`/`amin`/`argmax`/`argmin` compare with `>`. Historically rontolisp's `>` on two floats was
three different operations, so each kernel mirrored ITS OWN backend. The scalar comparisons were
fixed on all three (interpreter `compareNumeric` gained an UNORDERED state; the JVM literal path
picks DCMPG/DCMPL per operator and the runtime path uses the `_cmpb` bitmask; wasm's variable path
funnels through `_rat_cmp_bits`) and `LinalgSimdKernels` switched from `Double.compare` to plain
Java `>`/`<` **in the same change -- the required lockstep**.

The whole signed-zero surface reads identically on interpreter, JVM, wasm-GC, component AND
`--no-gc`, pinned by ci-spec `signed-zero-across-every-float-operator` and
`min-max-nan-is-unordered-so-the-right-operand-wins`. Settled answers (`nz` = `-0.0`):
`(+ nz nz)` = `-0.0`; `(min nz pz)`/`(max nz pz)` keep the LEFT operand on a tie; `(min nan x)` = `x`
but `(min x nan)` = `NaN`; `(signum nz)` = `-0.0`; `(sin nz)`/`(tan nz)`/`(tanh nz)` = `-0.0`;
`(mod nz 2.0)`/`(rem nz 2.0)` = `-0.0` but `(mod nz -2.0)`/`(rem -4.0 2.0)` = `0.0`;
`(eql nz pz)`/`(equal nz pz)` = `NIL`; `(linalg:amax #d(-0.0 0.0))` = `-0.0`.
- **`min`/`max` are one rule on both edge axes**: `min(a,b) = (a <= b) ? a : b`,
  `max(a,b) = (a >= b) ? a : b` with IEEE comparisons -- **verified against SBCL bit-for-bit over all
  98 ordered pairs from `{-0.0, 0.0, 1.0, -1.0, NaN, +inf, -inf}`**, CLHS leaving both the tie and
  NaN unspecified. Deliberately NOT `Math.min`/`f64.min`, which resolve the tie by SIGN and propagate
  NaN from either side. The literal fast paths survive (`JvmMinCompiler` calls `_fmin`/`_fmax`,
  `WasmMinMaxCompiler` emits `f64.le`/`f64.ge`); they compute the same select.
- **`eql`/`equal` on `-0.0` vs `0.0`** is `NIL` everywhere: wasm now compares BITS, OR-ed with a
  both-NaN arm so it stays identical to `Double.equals`.
- **`signum`/`sin`/`tan`/`tanh`** flattened on wasm because each computes by a route that erases the
  sign; each now guards the zero (and, for `signum`, the NaN) case. The `--simd` kernels
  (`WasmVecSimdRuntimeBuilder.emitSignumF64`/`emitSinCosF64`/`emitTanhF64`, shared by `vec:`,
  `linalg:` and `--no-gc`) carry the SAME guards, pinned byte-identical by
  `wasmGcSimdUnaryUfuncsAreByteIdenticalToTheScalarPath` and its `linalg` twin, which fail if only
  one side is changed.
- **Float CONTAGION in `min`/`max`**: **SBCL answers `1`** for `(min 1 2.0)`.
  `Environment.registerArithmetic`'s `min`/`max` no longer coerce, and the JVM's double-literal fast
  path was NARROWED, not dropped -- gated on `JvmLispCompiler.isDefinitelyDouble` (each operand
  independently PROVEN double) instead of `hasDoubleLiteral` ([[jvm-double-arithmetic]]).
- **`--no-gc` is a genuine, permanent exception**: its type inference gives every
  `+ - * mod rem abs min max` site ONE static WASM type -- `FLOAT` iff any operand is `FLOAT`
  (`NoGcWasmCompiler.typeOf`) -- so `(min 1 2.0)` is `1.0` there. Changing it means giving `--no-gc`
  a dynamic numeric tower, which is the reason `linalg:` cannot compile there at all.

## `mod` / `rem` and the floor family
**A zero remainder is the one CLHS's own definition produces, NOT IEEE `fmod`'s.** The remainder is
`a - b*q` for an exact INTEGER `q`, so **a zero remainder is `-0.0` only when the dividend is `-0.0`
and the divisor is positive, and `+0.0` otherwise**. Interpreter and JVM keep Java's exact `%`/`DREM`
and re-derive only the zero (`_frem`, which `_fmod` calls first, so `mod` and `rem` share one zero).

**The magnitude is NOT the f64 evaluation of that formula, and SBCL is not the oracle for it.**
`a - b*q` has ONE right value, always representable; evaluating in f64 rounds twice
(`(rem 1d18 7.0)` is `1.0` here and `0.0` on SBCL). CLHS's words decide the SIGN, the mathematics the
magnitude. Both wasm backends reach the exact `fmod` through `codegen/wasm/WasmFmodRuntimeBuilder`,
emitted into the wasm-GC `_rat_rem`/`_rat_mod` float arm and INLINED at the site by
`NoGcWasmCompiler.compileModRem` -- **one builder, so the two cannot drift**. An INFINITE divisor
takes no loop; a zero divisor is `NaN` on all five.

**Trap: `mod`'s sign correction must not multiply the operands.** Both the JVM's `_fmod` and the
first wasm draft tested "opposite signs" as `r * b < 0`; that product UNDERFLOWS to zero when both
are tiny, so the correction silently did not fire and `mod` answered `rem`'s negative remainder. All
backends now compare the two signs directly.

**The QUOTIENT is exact at every magnitude too.** It used to be the double `a/b` narrowed into a
`long`, which rounds twice and then CLAMPS. Now all four backends divide the operands AS THE EXACT
RATIONALS THEY ARE and round that rational, giving a bignum when it has to be. **SBCL is not the
oracle for the two-argument rows** (it rounds `a/b` in f64 first); the one-argument rows DO match.
**The change is not confined to huge magnitudes**: `(floor 1.0 0.1)` is now `9` with remainder
`0.09999999999999995`, because the double `0.1` is a shade above a tenth.

The second value is no longer `x - q*y`. `LispMacroExpander.lowerMvProducer` -- the ONE lowering all
four backends share -- reads each operator's remainder off `rem` and `mod`: `truncate`'s is
`(rem a b)`, `floor`'s `(mod a b)`, `ceiling`'s `(- (mod (- a) b))` (negating the DIVIDEND, not
subtracting a divisor from `mod`, which would round twice and lose a tiny dividend), with a zero
taking `rem`'s zero so the sign rule is not flipped by the negation, and `round`'s whichever of the
two its quotient landed on. A divisor of `1` covers the one-argument forms. Per backend:
`eval/ExactRounding` (reached from `LispEvaluator.evalCons`, which recognizes both `(op a b)` and the
`(op (/ a b))` its lowerings leave behind), the JVM's `_fdiv`/`_frat` (`JvmNumericRuntimeBuilder`),
and wasm-GC's `_f64_fdiv` (`WasmFloatFdivRuntimeBuilder`, handing two rationals to the limb-tier
`_big_fdiv`). All three DECLINE -- keeping the old f64 route -- for a ratio operand, a non-finite
dividend and a zero divisor.

**An INFINITE divisor is settled by sign, not declined.** With a FINITE NONZERO dividend `a/b` is an
infinitesimal under 1/2, so `truncate`/`round` are always `0` and `floor`/`ceiling` give `0`/`1` when
dividend and divisor agree in sign, `-1`/`0` when they do not. An EXACT-ZERO dividend still takes the
old route (`ExactRounding.infiniteDivisorQuotient`,
`JvmNumericRuntimeBuilder.emitInfiniteDivisorQuotient`,
`WasmFloatFdivRuntimeBuilder.emitInfiniteDivisorQuotient`).

Pinned by ci-spec `mod-rem-zero-is-the-truncate-and-floor-remainder`,
`mod-rem-are-the-exact-float-remainder-at-any-magnitude`,
`the-floor-family-quotient-is-exact-at-any-magnitude`;
`LispEvaluatorTest.theFloorFamilySecondValueIsTheRemainderCLHSDefines` (an exact-rational oracle
sharing no code with the implementation); and per-backend 240-pair DIFFERENTIALS against the
interpreter over a value set crossing 2^53, 2^63, the subnormals and both signed zeros
(`theFloatRemainderMatchesTheInterpreterOverAMagnitudeSweep`,
`theFloorFamily{MatchesTheInterpreterOverAMagnitudeSweep,QuotientWithAnInfiniteDivisorMatchesTheInterpreter}`).

**`--no-gc` cannot follow and is the one documented divergence** (two rows): i64-native with no
bignum tier ([[wasm-bignum]]), so `(floor 1d300)` TRAPS and `(truncate 1d18 7.0)` keeps the
rounded-double quotient; `(floor -3.0 inf)` stays `0` because it lowers to `(floor (/ a b))`. The
remainder side is unaffected. `ffloor`/`fceiling`/`ftruncate`/`fround` do not exist at all.

## Per-backend mechanics
**Interpreter.** `LinalgSimd.install(globalEnv, evaluator)` runs right after `linalg.lisp` is
evaluated in `LispEvaluator.resolveFunction`'s lazy-load hook, guarded by `this.simd`. Each override
**captures the defun it replaces** and applies it on decline through the package-private
`LispEvaluator.applyGlobal` seam; unwrap is zero-copy. `LinalgSimd.available()`/`install(...)` are
the ONLY entry points into `LinalgSimdKernels`, which is what makes
`src/web/java/.../Target_LinalgSimd.java` sufficient to cut the incubator Vector API out of the
browser Web Image. **A new public method on `LinalgSimd` that touches the kernels would break it, and
only the Pages workflow's Web Image build would notice**; `./mvnw -Pweb compile` is the local check.

**JVM.** **One bridge class** (`JvmSimdVectorTemplate`), so one `_simdInit` and one
`resource-config.json` entry -- a second template class would need its own entry and the failure
would be at RUN time. `JvmSimdRuntimeBuilder` registers the `la*` method refs under
**package-prefixed keys** (`"linalg:add"`; internal members under the double-colon spelling), because
`vec:add` and `linalg:add` share a member name. **`jdk.incubator.vector` is an optional module**:
`_simdInit`'s `Lookup.defineClass` resolves the template's verifier-visible types AT THAT CALL, so a
JVM without `--add-modules` fails to LINK the bridge -- before any bridge method runs, unlike
`--blas`/`--gpu`, whose probe is a method call inside an already-linked bridge. So every accelerated
call site checks `_simdReady()` BEFORE emitting a call that would resolve a method reference into the
bridge (`JvmSimdModuleFallbackTest` runs a compiled class in a child JVM with no `--add-modules`).
The compiled packed array carries an **in-array header** `[rank, dim..., data...]`, `off = 1 + rank`,
so an element-wise linalg kernel is the `vec:` one at a different offset and the fresh result must
copy the whole header (`laNewLike`). The gate `JvmLispCompiler.programUsesAnyAcceleratedSimdOp` scans
AFTER `LinalgLibrary.process` has spliced the defuns, so ANY linalg program embeds the bridge.

**wasm-GC.** Fifty-six standalone functions at `WasmLispCompiler.linalgFuncBase()` =
`FUNC_VEC_BASE + 55`, emitted only under `--simd`; `userFuncBase()` shifts by 111. Newest first:
`COMPARE_GT`..`SCALE` 45..55; `RNG_FILL` 43 and `ADAM_STEP` 44 (on the always-present five-eq-param
`TYPE_CALLABLE_BASE + 4` type `IM2COL` already used); `ERF` 42; `MATMUL_ND` 41; the seven
declined-shape helpers `BCAST`..`ARGMIN_AXIS` before those. **No new type entries were needed
anywhere.** Scratch lives in fresh `$hash_buckets` i31 arrays -- kernels cannot hold extra typed
local groups beyond the fixed `WasmVecSimdRuntimeBuilder.withLocals` order (i32, f64, f32, v128,
`(ref null eq)`, `(ref null $v128arr)`), which is also why they must be standalone:
`WasmLispCompiler` declares every extra local of a compiled defun as one `(ref null eq)` group.
`WasmLispCompilerTest.simdAppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks` pins the
delta -- the only structural guard that a build WITHOUT `--simd` stays byte-identical to one that
never knew the flag. **Update it, never weaken it.**

Kernel structure: a `block` per shape, `br` to the outermost block to decline (`res` defaults to
null); `ref.test (ref $farray)` on nil is false, so a nil argument declines for free. Three things
the `vec:` kernels did not have to do:
- **Compare dims, not just counts** (`emitDimsEqual`): `(2 3)` and `(3 2)` have the same count but
  the defun errors on them.
- **Copy dims into the result** (`copyDims`), copied not shared, matching `%la-like`.
- **Restore the last group's padding after a broadcast.** Over the zero padding `0 - s = -s`,
  `s / 0 = inf`, `0 / 0 = NaN` (that last is why `div` between two arrays needs it too); a later
  `sum` would fold the garbage in. `gcSaveLastGroup`/`gcRestoreLastGroupTail` do it once per call.

`f32x4.div` (`0xFD 0xE7`) was added to `am.ik.wasm.Instruction`, `WasmVecLoops.f32x4Of` and
`WasmSections.skipSimd` (which throws on an unknown `0xFD` sub-opcode, by design).

## Verification
- `eval/LinalgSimdTest` (48) -- the interception guard (`#'linalg:add` is `#<function linalg:add>`
  under `--simd`, `#<lambda>` without; `emap`/`inv`/`det`/`solve`/`array-equal`/`mean`/`matmul`/
  `flatten` stay `#<lambda>`), byte-identity vs the oracle at both widths and ranks, the declined
  inputs, the f32 probe, the declined-shape follow-up,
  `erfMatchesTheScalarOracleOverTheWholeRangeAtBothWidths`,
  `theAdamStepAndTheGeneratorFillAreInterceptedUnderSimd`,
  `theSelectsAndCopiesAre{InterceptedUnderSimd,BitIdenticalToTheScalarOracleAtEveryShapeAndWidth}`
  and `...DeclineWhatTheDefunSignalsAndSignalItUnchanged`. Two test-writing caveats: a long-fill case
  compares the ARRAY, not a `linalg:sum` of it (`sum` is itself a lane reduction under `--simd`), and
  the `torch:` tape case uses `torch:mul` rather than `torch:matmul`, because an f32 MATMUL is the
  one member in that chain that is NOT byte-identical under `--simd`.
- `codegen/jvm/JvmLinalgSimdAccelCompilerTest` (34) -- the bridge-embedded dead-flag guard, the same
  byte-identity set, the evaluate-once guards (base AND extended call sites), the library errors
  still signalling.
- `codegen/wasm/WasmLispCompilerIntegrationTest` (Docker + wasmtime), eleven `wasmGcSimdLinalg*`
  cases, including `...LaneProductsMatchTheScalarPathAtEveryRowLaneOffset` (every shuffle-offset
  variant via a 7-column `#f` matrix, the odd-`p` sentinel-group write, aligned vs unaligned
  outer/transpose, a next-row inf inside the window overhang).
- `ci-spec.yaml` never passes `--simd`, so the cross-backend E2E is unaffected; the
  `--component --simd` and `--optimize` legs were verified by hand and by the integration test.
- `examples/ml/{tiny-llm,linear-regression,heat3d,deep-digits}.lisp` and
  `examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` print byte-identical output with and
  without `--simd` (the latter on ALL FOUR backends), and at the notebook's shapes also on a
  `--gpu --simd` JVM class -- the acceptance that matters for two members whose kernels write their
  operands IN PLACE.

## Not done
- The lane-unaligned `outer` (`m % lanes != 0`) and `transpose` shapes keep the element loop, as do
  `amax`/`amin`/`argmax`/`argmin`/`trace` and the single-float scalar broadcast. A blended-edge lane
  form was judged not worth the shuffle bookkeeping.
- A linalg program dominated by `emap`/`inv` still pays `_v_get`/`_v_set` on wasm-GC and stays slower
  under `--simd` than without it. That penalty is **intrinsic**: a `v128` can only be read out of an
  `(array (mut v128))`, never out of an `(array (mut f32))`, so no representation is fast for both
  lane loops and scalar element access. Uncosted follow-up: keep `(array (mut f64))` under `--simd`
  and gather lanes with 2 or 4 `array.get` + `replace_lane` per group -- that would delete the
  representation switch and every un-intercepted regression, at maybe 2-4x the kernel cost. It
  contradicts the original representation choice; measure before believing either.
- A possible `emap` special case when `f` is a *known builtin*. `linalg:emap #'silu` in
  `tiny-llm.lisp` is a user lambda and would not benefit.
