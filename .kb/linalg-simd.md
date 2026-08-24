# `linalg:` kernel interception (`--simd`)

The `--simd` acceleration of the `linalg:` package (todo-107, 2026-07-10). Read `.kb/vec.md`
first: this is "do what `vec:` does, for `linalg:`", and it reuses the `vec:` lane loops
rather than copying them. `.kb/linalg.md` has the semantics of the library being
accelerated.

Three backends, one per interception mechanism:

| backend | interceptor | kernels | without `jdk.incubator.vector` |
|---|---|---|---|
| interpreter (`prog.lisp --simd`) | `eval/LinalgSimd` (re-`defineFunction`) | `eval/LinalgSimdKernels` (jdk.incubator.vector) | `VecSimd.available()` probes first; `RontoLispCli.enableSimd` warns once and leaves `evaluator.setSimd` off, so `LinalgSimd.install` never runs -- the scalar defuns stay bound |
| JVM (`-o Prog.class --simd`) | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmSimdVectorTemplate.la*` (the one embedded bridge) | `_simdInit` catches the `LinkageError` `Lookup.defineClass` raises, warns once, leaves `_simdAvailable` false; every call site checks `_simdReady()` (the `ops.get("available")` methodref) BEFORE resolving a reference into the bridge, and skips straight to the next rung / scalar defun when it reads false |
| wasm-GC (`-o prog.wasm --simd`) | `codegen/wasm/WasmLinalgSimdCompiler` (call site) | `WasmLinalgSimdRuntimeBuilder` (45 emitted functions) | n/a -- the kernels are emitted wasm functions, not an external module; nothing to be missing |

`--no-gc` is out of scope: `linalg:` cannot compile there at all (`linalg::%la-make` uses
`&optional`, and `--no-gc` has no general array type).

`--blas` (`.kb/linalg-blas.md`) is a SECOND flag over this same seam, and `--gpu`
(`.kb/gpu.md`) a THIRD -- both on the interpreter and the JVM only, since both call out
through the foreign function API: they put a tuned CBLAS's `gemm`, and a device product
ahead of that, in front of the lane kernel for `linalg:dot` -- and since 2026-08-21 `--gpu`
also takes `%la-matmul-nd`, the twelve element-wise members whose scalar cost is a libm
call (`exp` `log` `tanh` `sin` `cos` `tan` `asin` `acos` `atan` `sinh` `cosh` `erf`), and
the ten members whose CPU kernel BELOW is a scalar odometer walk rather than a lane loop
(`add` `sub` `mul` `div` `maximum` `minimum` at a BROADCAST shape, `sum` `amax` `amin`
with `:axis`, `transpose` with an axes list), where it has no `--blas` neighbour (those
chains are device -> lane kernel -> defun). **That last set is chosen against the CPU
column in THIS file**: the same `linalg:sub` is a device member against a `(4 256 1)`
operand and a decline against a same-shaped one, because the first takes the odometer
branch below and the second the lane loop. That
is why the JVM
interceptor is named `JvmLinalgKernelCompiler` rather than `JvmLinalgSimdCompiler` -- it
emits a CHAIN of up to three attempts over one set of temps, ending at the scalar defun,
and since 2026-08-21 it emits that chain at the EXTENDED (option-form) call sites too.
Nothing below changes: with `--blas` and `--gpu` off, a `--simd` build emits and computes
exactly what it did before. A FOURTH flag, `--parallel` (`.kb/simd-parallel.md`), is a modifier of
`--simd` rather than a rung of that chain: the matrix products (`dot`'s matrix cases and
`%la-matmul-nd`) run a row range per thread and stay bit-identical; nothing else moves.

**`--gpu` is the one flag over this seam whose element-wise results are NOT bit-identical
to the defun** -- for its TRANSCENDENTAL tier only; its strided tier (the broadcast pairs,
the axis folds and the axes transpose) widens to double, computes in double and narrows on
the store exactly as the kernels below do, and is bit-identical at both widths. For the
transcendentals the break is real, and this file states the opposite contract for every
member of its own set:
the device has its own libm, and at `#f` it evaluates AT the operand width where these
kernels evaluate in double and narrow. `.kb/gpu.md` carries the per-member divergence
table and the tolerance its tests pin. Nothing in the precision contract BELOW moves --
a call the device declines is still the lane kernel's, and the lane kernel is still
bit-identical -- but a program run under `--gpu` is no longer comparable byte for byte
with one run under `--simd`, and `.kb/gpu.md` says what replaced that check.

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
- arrays of DIFFERENT shapes: the defun broadcasts them by the numpy rules
  (`%la-bcast-loop`, see `.kb/linalg.md`) and signals the specific shape-mismatch
  `error` only when no broadcast fits. Since the todo-117 declined-shape follow-up
  (2026-07-13) a SAME-width broadcast pair is handled by the kernels themselves: the
  element-wise kernels' unequal-dims branch runs the general numpy odometer walk
  (`bcast`/`laBcastDD`/the wasm `BCAST` helper) -- every element read widened to
  double, computed in double, narrowed only by a store into a single-float result,
  which is `%la-bcast`'s own emap rule, so bit-identical at both widths. A MIXED-width
  or incompatible pair still declines (the defun widens / signals).

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
- **An intercepted member may be VARIADIC** (the deep-learning-from-scratch port gave
  `sum`/`mean`/`amax`/`amin`/`argmax`/`argmin` `&key axis keepdims` lambda lists, which
  `LambdaLists` desugars to a trailing `&rest` parameter), and since the todo-117
  declined-shape follow-up six of them have EXTENDED (option-form) call sites:
  `transpose` with a positional axes list (`laTransposeAxes` / `TRANSPOSE_AXES`),
  `sum`/`amax`/`amin` with `:axis` / `:keepdims` (`laSumAxis` &c / `SUM_AXIS` &c),
  `argmax`/`argmin` with `:axis`. The kernels stay POSITIONAL (`laSumAxis(a, axis,
  keepdims)`); `compiler.LinalgKernelCallLayout.layout` -- shared by both codegens so
  they pattern-match identically -- maps the call's argument forms onto the kernel
  parameters: literal `:keyword value` pairs over the member's declared keywords, each
  at most once, in any order, a missing option padded with null = nil at the call site.
  Anything else (a non-literal keyword, an unknown or repeated one, an odd tail, a
  positional count beyond the shape) is NOT a kernel call and routes to the ordinary
  direct-call path, where the defun's `&key` prologue signals. The decline branches
  package the rest list from the SAME temps -- keyword literals included, so the defun
  sees `(:axis 0 :keepdims t)` exactly: the base arity pushes an EMPTY rest
  (`ACONST_NULL` on the JVM, `ref.null eq` on wasm), an extended call links the surplus
  temps into a cons chain (`Object[2]` cells on the JVM, `struct.new $cons` on wasm) --
  both compilers also verify the defun's required count still matches the base arity
  (and, for an extended call, that the defun is variadic) and bail to `compileDefault`
  otherwise. The interpreter needs the arity-RANGE guard in `LinalgSimd.define` (1..5
  for sum/amax/amin, 1..3 for argmax/argmin) plus `LinalgSimd.options`, the runtime
  twin of the layout (a malformed tail declines); a declined call falls through to
  `applyGlobal`, which binds the rest list normally.
  Pinned by `axisFormsRunTheAxisKernelsAndMatchTheScalarReference` +
  `anAxisArgumentFormIsEvaluatedExactlyOnceEvenWhenTheExtendedKernelDeclines` (JVM),
  `wasmGcSimdLinalgAxisFormsRunTheAxisKernelsAndMatchTheScalarPath` (wasm) and
  `axisFormsRunTheFoldKernelsAndMatchTheScalarOracle` (interpreter).

## The intercepted set (49 members)

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
section of `.kb/vec.md`), plus the todo-117 INTERNAL pair `%la-im2col` (arity 5) /
`%la-col2im` (arity 6) -- the CNN window unfolding behind the Deep Learning from
Scratch convolution examples -- and the todo-467 INTERNAL `%la-matmul-nd` (arity 2),
the stacked rank->=3 matrix product (below), plus the todo-468 `erf` (arity 1), the
error function behind the exact `torch:gelu` (below -- the one member whose DEFUN is an
`emap`, which is why the member itself had to be intercepted), plus the todo-473 INTERNAL
pair `%la-rng-fill` (arity 5) / `%la-adam-step` (arity 5) -- the seeded generator's one
fill loop and Adam's fused element-wise update (below), plus the 2026-08-22 SELECTS AND
COPIES (below): the comparison masks `greater` `greater-equal` `less` `less-equal` `equal`
(arity 2), `where` (arity 3), `take-rows` (arity 2), and the INTERNAL `%la-gather-strided`
(arity 5, the one strided read behind `slice` and `%la-broadcast-to`), `%la-scatter-rows`
(arity 3, `take-rows`' scatter-add adjoint), `%la-sum-squares` (arity 2) and `%la-scale`
(arity 2, `torch:clip-grad-norm`'s two halves). Those are the intercepted internal members: a
`%`-prefixed member's canonical qualified spelling carries the DOUBLE colon
(`linalg::%la-im2col`), which is how the interpreter's function binding, the compilers'
`ctx.functions` keys and the emit-gate symbol scan must compose it --
`Jvm/WasmLinalgSimdCompiler.qualifiedName` and the interpreter `LinalgSimd.define` all
branch on the `%` prefix. Their kernels are pure index-arithmetic loops (no lanes;
im2col is a copy, col2im accumulates two same-width elements per store, which at f32 IS
the defun's widen-add-narrow round trip -- the exact f64 sum of two f32s narrows to the
correctly rounded f32), so both are bit-identical at both widths. They decline anything
but a rank-4 packed operand (col2im: any-rank packed col + a literal 4-list of dims
whose product the col size must match exactly) with i31/integer window parameters --
positive filter/stride, non-negative pad, and both padded extents `h + 2*pad - fh` /
`w + 2*pad - fw` non-negative, so the defun's `floor` is a plain truncating division in
the kernel. The JVM bridge descriptors are built per arity now (5/6 `Object` params),
and the wasm kernels use the always-present `TYPE_CALLABLE_BASE + 4` / `+ 5` function
types, so no new type entries were needed.

**The declined-shape follow-up (2026-07-13)** closed the three shapes that still ran
the boxed defuns under `--simd` (at the SimpleConvNet sizes they were ~97% of the
residual ch07 train time on the interpreter): the general numpy BROADCAST between two
same-width packed arrays (reached from the element-wise kernels' unequal-dims branch,
`maximum`/`minimum` included), `transpose`'s rank-n AXES form, and the AXIS forms of
`sum`/`amax`/`amin` (axis + keepdims) and `argmax`/`argmin` (axis). The member set is
unchanged (still 34); what grew is the set of intercepted CALL SHAPES (the multi-arity
call sites above) and the wasm function block (34 -> 41: `BCAST`, `TRANSPOSE_AXES`,
`SUM_AXIS`, `AMAX_AXIS`, `AMIN_AXIS`, `ARGMAX_AXIS`, `ARGMIN_AXIS`). All of them are
deliberately SCALAR walks (odometer copies and folds, `_v_get`/`_v_set` element loops
on wasm): the win is de-boxing, and scalar double arithmetic is what makes them
bit-identical (below). **Being scalar walks is also what made them `--gpu`'s phase-3
member set** (`.kb/gpu.md`): a shape that costs the CPU an odometer step per element
rather than a lane's worth pays for a round trip 3-8x over at a transformer's shapes,
where the same member at an equal shape does not pay at all. If a lane form is ever
written for the row-broadcast case -- `(n m p) op (n m 1)` is a splat per row and is the
single hottest kernel in a `--gpu --simd` training step -- re-run
`.todo/123-gpu-acceleration/{shaped-baseline.lisp,StridedCrossover.java}`, because it
would move that member's crossover. Declines: a nil/non-integer/out-of-range axis, an empty axis
(the vector `sum` of an empty axis would need the defun's INTEGER 0), a non-permutation
axes list, a mixed-width or non-broadcastable pair, any general boxed operand.

Accelerated **transitively**, so they are not intercepted directly: `rand` / `randn` /
`uniform` (all three call `%la-rng-fill`), `torch:step` over a `torch:adam` / `torch:adamw`
optimizer (calls `%la-adam-step` once per parameter), `mean` (calls `sum`),
`matmul` at EVERY rank (rank <= 2 calls `dot`; rank >= 3 calls
`%la-matmul-nd`, itself intercepted since todo-467), `flatten` (calls `reshape`), `solve` (calls `inv` then `dot`),
`square` (calls `mul`), `reciprocal` (calls `div`), `clip` (calls `maximum` then
`minimum`), `relu` (calls `maximum` with the 0.0 bound).

**Never** intercepted: `emap` (an arbitrary Lisp callback), `det` / `inv` / `solve`'s
pivoting elimination (data-dependent pivots, sequential column dependency), `array-equal`
(the nil-return sentinel collision), and the constructors.

### The stacked matrix product (`%la-matmul-nd`, todo-467, 2026-08-20)

`linalg:matmul` routes rank <= 2 to `dot` and rank >= 3 to `linalg::%la-matmul-nd` --
`torch.bmm`, hence every attention layer and every `torch:linear` over a `(B T C)`
activation, which for a transformer is essentially the whole forward and backward pass.
Until todo-467 that member was a boxed `outer x M x K x N` walk built from no intercepted
member, so `--simd` did nothing for it on the interpreter and the JVM and made it ~11%
SLOWER on wasm-GC (it paid the `TYPE_VBLOCK` `_v_get`/`_v_set` cost this file opens with
and got nothing back).

**`--gpu` intercepts this member too** (2026-08-21, `.kb/gpu.md`'s intercepted set; it
took the todo-109 unary ufuncs and `erf` the same day), and the batch odometer is exactly
where the two differ: the device walks the batch with ONE stride per operand, so a broadcast leading axis is stride 0 as it is here,
but a broadcast axis sitting UNDER a non-broadcast one is a decline there and lands back
on this kernel. The threshold there is the TOTAL work, `batch*n*m*p`, since a stack is one
round trip.

**The kernel is `dot`'s M.M lane loop, once per batch offset** -- not a second lane loop.
All three backends factored the rank-2 product into an offset-taking slab
(`LinalgSimdKernels.matmulInto`, `JvmSimdVectorTemplate.laMatmulInto`, and on wasm the
already-shared `emitGemmRow` over a scratch accumulator row) and the batched kernel calls
it per batch. The offsets come from the `%la-batch-strides` odometer, which is
`%la-bcast-strides` scaled by the trailing matrix size -- so a BROADCAST leading axis has
stride 0 and needs no special case at all (on wasm that scaling is the `baseLocal`
parameter `emitAlignedStrides` gained; `emitBcastShape` was factored out of `buildBcast`
so the batch shape reuses the element-wise broadcast rule over the leading axes only).

Declines, all of which the defun then answers -- keeping the dispatch, the scalar
rejection and both error messages in the library, where they were: a general boxed
operand, mixed widths, a RANK-1 operand on either side (the numpy
promote-then-drop-the-axis rule -- cheap to add later, but it is not the hot shape and
keeping it in the defun keeps the kernel one shape), a non-broadcastable batch shape, a
mismatched inner dimension, and any empty extent (a zero-length `k` fold answers the
defun's INTEGER `0` seed).

**Precision is a per-batch `linalg:dot`, not the defun.** Each cell folds `k` in the
defun's ascending order at the OPERAND width, so `#d` is bit-identical and `#f` follows
the reduction contract below. Pinned by the rank-3 line of the f32 probe in all three
suites: `(linalg:matmul (reshape v '(1 1 1024)) (reshape v '(1 1024 1)))` prints
**16777216** on every `--simd` backend against the scalar 16778240 -- the same number the
v.M `dot` probe lands on, which is exactly the claim.

Measured (aarch64 DGX Spark / GB10 Grace, GraalVM 25, wasmtime 47; six rounds with every
round printed, the stable median quoted; `n = 64`):

| ms/call | scalar `#d` | `--simd` `#d` | scalar `#f` | `--simd` `#f` |
|---|---|---|---|---|
| interpreter, rank 2, 4 x 64x64 | 789 | 0.21 | -- | -- |
| interpreter, rank 3, (4 64 64) | 1197 | 0.19 | -- | -- |
| interpreter, rank 3, (16 64 64) | 4820 | 0.85 | 4794 | **0.50** |
| JVM, rank 2, 4 x 64x64 | 1.045 | 0.18 | -- | -- |
| JVM, rank 3, (4 64 64) | 1.535 | 0.21 | -- | -- |
| JVM, rank 3, (16 64 64) | 6.15 | 0.833 | 10.40 | **0.500** |
| wasm-GC, rank 2, 4 x 64x64 | 36.99 | 0.945 | -- | -- |
| wasm-GC, rank 3, (4 64 64) | 36.56 | 0.945 | -- | -- |
| wasm-GC, rank 3, (16 64 64) | 147.4 | 3.78 | 146.9 | **2.24** |

Two things to read out of it. The wasm-GC rank-3 row **stopped being slower than scalar**
(the regression this file opens with -- `emap` / `inv` still pay it, see "Not done"), and
`#f` is now the FASTER width on the batched shape everywhere -- the ranking was inverted
(6.15 `#d` vs 10.40 `#f` on the JVM) for exactly as long as the member was un-intercepted,
because the boxed walk widened on every read and narrowed on every store. **Intercepting
this member is what let todo-123 phase 0 pay off**: `train-gpt-soseki.lisp` under `--simd`
went `#d` 1.67 / `#f` 1.81 s (phase 0's failed acceptance) to `#d` 0.74 / `#f` **0.70** s
on the JVM, and with `*max-steps*` 5000 so the run is training-dominated, `#d` 7.26 /
`#f` 7.57 -> `#d` 4.59 / `#f` **3.92**. Interpreter `--simd`: 31.63 -> **6.53** s.

### The error function (`erf`, todo-468, 2026-08-21)

`linalg:erf` is `(linalg:emap (function linalg::%la-erf-1) a)`, and `emap` is **never**
intercepted -- its callback is arbitrary Lisp. So erf was the one member of the
activation-primitive group (`relu`, `softmax`, `log-softmax`, `erf`) that got nothing from
the flag, while `torch:gelu`'s DEFAULT (`:approximate :none`, matching `nn.GELU`) is built
on it: every transformer feed-forward block. The `:tanh` form is mul/add/tanh and was
already fully accelerated, so the exact GELU was the slow one, which is backwards.

**The kernel is `%la-erf-1`'s own arithmetic, in the defun's order, and therefore
bit-identical at both widths** -- the `|x| >= 6` short circuit, the all-positive-term
A&S 7.1.6 series `term = term * 2x^2 / (2n+1)` broken at `term < 1e-17 * total` and capped
at `n = 200`, then `1.1283791670955126 * |x| * exp(-x^2) * total` with the sign applied
last. It computes in DOUBLE at both widths and narrows only on the store, because that is
`emap`'s rule and the defun's; accumulating the series in single would be the opposite of
what the `#f` REDUCTION contract says elsewhere in this file, and a silent cross-backend
divergence. Declines anything but a same-width packed `#d`/`#f` operand (a general boxed
array, a plain number), which the defun then answers.

Two spellings are per-backend, mirroring each backend's own compiled defun rather than
each other -- the same rule the todo-109 ufuncs follow. `(abs x)` and `(- v)` have no
double LITERAL among their argument forms, so on wasm they take the generic path:
`abs` is `_rat_cmp`'s float compare, `x < 0 ? 0 - x : x`, which leaves `-0.0` ALONE where
`Math.abs` folds it to `0.0`, and unary minus is `_rat_sub(0, x)`. So
`(linalg:erf #d(-0.0))` is `#d(-0.0)` on wasm and `#d(0.0)` on the interpreter and the
JVM -- in the DEFUN, before any kernel existed, and each kernel matches its own. `exp` is
the same story: `Math.exp` on two backends, `WasmExpCompiler`'s Horner approximation
(emitted by `emitExpF64` from the same constants) on the third.

**No lane form, and the reason is not "no v128 instruction".** The per-element iteration
count is DATA-DEPENDENT (it grows with `x^2`), so a lane loop must run every lane to the
maximum of its group's counts and mask the ones that have broken. Measured before writing
one, on this box (aarch64 / NEON, **2** f64 lanes), 2^20 standard-normal elements, six
rounds:

| f64 | scalar de-boxed loop | masked lane series |
|---|---|---|
| lane `exp` (`VectorOperators.EXP`) | 117 ms | **1600 ms** |
| scalar `Math.exp` per lane, warm | 118 ms | 98 ms |

The first row is disqualified twice over: 14x slower AND not bit-identical to `Math.exp`
(which is why `linalg:exp` itself is a de-boxed scalar loop -- see the table above). The
second is the honest ceiling: **1.20x**, and only once JIT-warm -- two of the six rounds
ran 13-15x slower while the `lane(k)` extraction deoptimized. Against that, INTERCEPTION
alone buys 132x on the interpreter and 13x on wasm-GC. The `%la-im2col` precedent holds:
the win is escaping the tree-walk and the boxing, not v128. Do not spend time on a masked
lane form; if it is ever revisited, measure on a machine with 4+ f64 lanes first.

Measured (aarch64 DGX Spark / GB10 Grace, GraalVM 25, wasmtime 47; one `linalg:erf` over
the notebook's feed-forward activation shape `(4 256 1536)` = 1.5 M elements, 3 reps):

| ms/call | scalar `#d` | `--simd` `#d` | scalar `#f` | `--simd` `#f` |
|---|---|---|---|---|
| interpreter | 20417 | **155** | 20584 | **151** |
| JVM | 200 | 159 | 160 | 151 |
| wasm-GC | 2031 | **154** | 1946 | **156** |

The JVM row is the one to read carefully: 1.26x, because the compiled defun's boxing is
small next to the series itself, which is division-latency bound (~15 dependent f64
divides per element). The interpreter's 132x and wasm-GC's 13x are the whole point --
there the boxed `emap` walk dominated.

End to end, `examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` at the NOTEBOOK's
shapes (`*n-embd*` 384, `*block-size*` 256, `*max-steps*` 5), interpreter, wall clock:

| interpreter, 5 steps | before | after |
|---|---|---|
| `--simd` | 332.3 s | **172.1 s** |
| `--gpu --simd` | 329.9 s | **171.5 s** |

**1.93x, and it closes the todo-123 phase-4a finding**: the reason the device bought
nothing on the interpreter was that ONE exact `gelu` cost ~21 s against 0.007 s for the
matmul it had just taken. That
call is now 0.155 s. The device still buys nothing on the interpreter -- the lesson stands,
only the member that dominates has changed -- and the original todo-468 sizing note
("this is NOT the bottleneck at the shapes the examples test") was written against the
example's own small defaults and stopped being true the moment todo-467 took the stacked
product out of the way.

### The optimizer update and the generator (`%la-adam-step` / `%la-rng-fill`, todo-473, 2026-08-22)

Both members are here for the same reason and neither is numpy: **the seam intercepts
`linalg:` names and nothing else**, and a JFR profile of `train-gpt-soseki.lisp` under
`--gpu --simd` said the remaining cost was two boxed Lisp loops that were not `linalg:` at
all: `torch::%o-adam-step` at 22-31% and `linalg:rand`/`randn` at 16%, with `_dbl` and `_fvAset1` -- the boxing and the
`(setf (row-major-aref ...))` they drive -- on top. **So the loops moved to `linalg:`
rather than the seam widening to `torch:`.** That was the first of the item's two open
decisions, and it is the cheap answer: no new touch point in any of the three backends,
one new intercepted member each, and `%la-im2col` is the precedent -- an internal `%la-`
member that exists for a library ABOVE this one.

The alternative the item names, rewriting Adam's rule over whole-array `linalg:` members
that ARE intercepted, was not measured and does not need to be: it is a dozen whole-array
temporaries per parameter per step against a fused loop with none, and the record already
says a fresh array per parameter per step is the allocation that dominates a small
training loop (`.kb/torch.md`).

**`linalg::%la-adam-step (x g m v ps)`** is the defun's own inner loop, over four aligned
same-width arrays, with the whole rule in an eleven-element packed double vector: `lr`,
`lr*wd`, `wd`, `b1`, `1-b1`, `b2`, `1-b2`, `eps`, `c1`, `c2`, `mode`. Two things about
that vector are load-bearing:

- **`mode` replaced the two booleans.** 0 = no weight decay, 1 = COUPLED
  (`torch.optim.Adam`: the L2 term rides the gradient), 2 = DECOUPLED
  (`torch.optim.AdamW`: the parameter shrinks on its own). `wd = 0` collapses both spellings
  to the same arithmetic, which is why three values cover the four combinations.
- **`lr * wd` is multiplied by the CALLER**, while both may still be exact rationals.
  `(* lr wd x)` is a left fold, so the scalar rule formed `(double)(lr*wd) * x`; storing
  `lr` and `wd` separately in a double vector and multiplying in the kernel would round
  twice and move a run with `:lr 1/100`. Every other hyper-parameter meets a double
  exactly once either way, so the vector may hold it narrowed.

It is bit-identical at both widths, by the rule this file repeats: every element is read
widened to double, the five multiplies, the `sqrt` and the divide run in double, and only
the store into a single-float parameter or moment buffer narrows -- which IS the boxed
defun's widen-compute-narrow round trip. Note `mk` / `vk` feed the parameter update at
full double width even at `#f`, because the defun's own locals do. Declines, all answered
by the defun: a scalar parameter or gradient (a plain number -- the one branch the defun
keeps), a general boxed array, a mixed-width quadruple, unequal element counts, a
malformed rule vector. **The kernel mutates in place, so every check is up front**: there
is no mid-loop decline, and a declined call must have written nothing.

**`linalg::%la-rng-fill (out st mode lo span)`** is the one fill loop `rand` / `randn` /
`uniform` now share, and the shape of its signature is the whole trick. The generator's
state lives in three SPECIALS, which a kernel on this seam cannot read or write; passing
the state in as an ARRAY and answering the state it ends on as one makes the fill a pure
function of its arguments. `%la-rng-state` reads the specials into a vector,
`%la-rng-restore` writes one back, and the three callers bracket the fill with them. The
generator's rule does not move: the DEFUN still calls `%la-rng-next` in a loop, so there is
still exactly one copy of it, and the scalar draws (`%la-rng-int`, hence `choice` /
`permutation`, and `seed`'s ten discarded draws) keep using it directly. `mode` picks the
element rule -- 0 one uniform draw, 1 the sum of twelve minus 6 (Irwin-Hall), 2
`lo + span * draw` -- each spelled exactly where its caller spelled it, `(- acc 6.0)` and
`(+ lo (* span u))` included.

**The item's second decision -- the closed form -- was not needed.** `s <- a*s mod m` has
`s_k = a^k * s mod m`, so a kernel COULD fill element k in any order; none of the three
does. Each keeps the three states in integer locals and walks the elements in order, which
is bit-identical by construction and is where the win already is: what the defun paid was a
boxed double per draw (twelve per `randn` element) and a boxed integer per state update,
not the sequencing. Keep the closed form in mind only for a device kernel, where the order
really would have to change. The kernel declines a state vector that is not three packed
doubles that are exact non-negative integers below `2^23` -- the range `linalg:seed`
produces and `%la-rng-next` keeps, and the range in which `a * s` cannot overflow an `int`
and Java `%` / wasm `i32.rem_s` agree with Lisp `mod`. It also declines a boxed
destination, a mode outside 0..2 and a non-numeric `lo` / `span`. **`linalg:seed`'s promise
is what makes byte identity non-negotiable here**: one seed reproduces one sequence on
every backend, and the examples' expected output is pinned to it.

Measured on the aarch64 DGX Spark / GB10 Grace, GraalVM 25, `--gpu --simd` JVM class
output, `examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` at the notebook's
`*block-size*` 256 / `*n-embd*` 384, three interleaved rounds of a 40-step and a 5-step run
(the medians of `(t40 - t5) / 35`):

| per training step | before | after |
|---|---|---|
| `--gpu --simd` | 0.326 s | **0.149 s** |
| `--simd` | 0.872 s | 0.834 s |

**2.2x on the device build and 1.05x on the CPU-only one**, and the gap is not noise: once
`--gpu` has taken `linalg:`, these two loops ARE the step, while under `--simd` alone the
matrix product still is. The 5-step run -- setup-dominated, and setup is one `linalg:randn`
per weight matrix -- moved 6.8 -> 3.0 s on the device build and 9.5 -> 5.8 s on the CPU
one, which is the generator alone. The JFR frames the item was filed on are gone:
`TORCH::%O-ADAM-STEP` 339 samples of 1514 -> `laAdamStep` 16 of 590, the three RNG frames
242 -> `laRngFill` 52, `_dbl` 150 -> 28, `_fvAset1` 221 -> 24 (`.kb/gpu.md` carries the
whole table).

Per member, 590k elements (`(384 1536)`, the notebook's feed-forward shape), best of five:

| ms/call | interpreter scalar | interpreter `--simd` | JVM scalar | JVM `--simd` |
|---|---|---|---|---|
| `%la-adam-step` | 2164 | **~0** | 4 | 3 |
| `linalg:randn` | 9408 | **55** | 80 | 55 |
| `linalg:rand` | 941 | **5** | 10 | 6 |

The interpreter column is the point of interception, as everywhere in this file. **Read the
JVM column against the end-to-end table above and not on its own**: a micro-benchmark calls
the member with ONE set of arrays, where escape analysis scalarizes the boxing away; in the
training program the same loop runs over dozens of differently shaped arrays and does not
get that, which is why the profile above found 22% where this table suggests 2%.

`torch::%o-sgd-step` was deliberately NOT moved: it is the same shape of loop, but it is on
no profile -- nothing in `examples/` trains with momentum at a size where it would show --
and a second fused member is only worth writing when something measures it.

`#'linalg:dot` still names the scalar defun on the compiled backends -- the interception is
at the *call site* there, while the interpreter overrides the *function binding*. So a
`linalg:` function passed to `funcall` / `mapcar` is not accelerated when compiled. Same
behavior as `vec:`, deliberately.

### The selects and copies (2026-08-22): comparison masks, `where`, the strided gather, `take-rows` and its adjoint, `clip-grad-norm`'s halves

Eleven members in one round, and they are here for the reason todo-473's two were: a JFR
profile of `train-gpt-soseki.lisp` under `--gpu --simd` at the notebook's shapes, taken
AFTER todo-473 landed, said that 40% of what was left of a training step was boxed
`row-major-aref` walks that were not intercepted at all --
`linalg:where` through `torch:masked-fill` (and its `%la-broadcast-to` ->
`%la-gather-strided` materializations, 22% on their own), `linalg:greater` through the
dropout mask (6%, the `emap` branch of `%la-bcast`), `linalg:slice` through `torch:cat`'s
adjoint (3%), `torch:index-select`'s inline scatter-add (5%), `torch:clip-grad-norm`'s two
loops (4%, 17% once everything else had moved) and `take-rows` (2%). None of them is
arithmetic: every one is a select, an IEEE compare, a copy or a widened add, so every one
is **bit-identical to its defun at both widths by construction**, and the round needed no
precision decision at all.

- **The five comparison masks** ride the element-wise dispatch: the same three `%la-bcast`
  shapes (equal dims, a scalar on either side, a broadcast pair through the BCAST walk
  with a new op code, `BOP_GT`..`BOP_EQ` = 6..10 in `LinalgSimdKernels`, the JVM template
  and the wasm builder alike), a 1.0/0.0 result at the first ARRAY operand's width (the
  `emap`'s), and the comparisons are the defun's own IEEE `>` `>=` `<` `<=` `=` on the
  widened elements -- so `-0.0` equals `0.0` and NaN compares false everywhere, exactly
  as `.kb/linalg-simd.md`'s `>` section says the scalar comparisons now do. NOT
  symmetric, so the scalar-on-the-left shape runs the reversed loop like the selects do.
  Two numbers decline (the defun answers an integer).
- **`where` (arity 3)** is a three-operand broadcast walk: each operand an array of
  either width or a number, the output shape folded pairwise over the array operands,
  stride 0 on a stretched axis and no stride at all for a scalar, `(= mask 0)` as an IEEE
  `== 0.0` test, and the result at `x`'s width when `x` is an array, else `y`'s, else
  double -- the defun's rule. No array at all declines (the defun answers a number), an
  incompatible broadcast declines (the defun's shape error), mixed widths among the
  operands are taken (each element is read widened anyway). A kernel, not a
  materialization: the defun builds three broadcast copies first and then selects, which
  is why it was a fifth of a step on its own.
- **`%la-gather-strided (a od rs base single)`** -- note the FIFTH PARAMETER CHANGED: it was
  the element-type symbol `etype`, it is now a flag (`nil` = double, non-nil = single).
  The defun turns the flag back into the `%la-make` symbol; the two callers pass
  `(eq (linalg::%la-etype a) 'single-float)`. The change is what made the member
  interceptable on all three backends without a symbol comparison: on the JVM a quoted
  symbol is a `String`, on wasm an interned `$string` offset, on the interpreter a
  `LispSymbol`, and a nil/non-nil test is the one thing all three answer the same way.
  The kernel parses `od` (a shape designator) and `rs` (a proper list of i31/integers, the
  innermost-first strides -- NEGATIVE for a reversed slice), reverses `rs` into
  outermost-first strides, and **computes the walk's lowest and highest flat index up
  front** (the base plus each axis's full negative or positive travel, in `long` on the
  JVM/interpreter and in f64 on wasm), declining when either leaves `a` -- so a declined
  call has read nothing and the defun signals its own subscript error per element as it
  always did. An empty output (a 0 extent) walks nothing and is always in bounds.
- **`take-rows (a idx)` and `%la-scatter-rows (z g idx)`** read the index vector exactly as
  the defun does -- `(truncate (aref idx i))` -- and decline when it is not a rank-1 packed
  vector or when any truncated index leaves `[0, rows)` (the defun's subscript error); the
  check is `v > -1.0 && v < rows` on the double, so `-0.5` truncates to 0 like the defun's
  `truncate` and NaN declines. `%la-scatter-rows` is NEW in `linalg.lisp`: it is the loop
  `torch:index-select`'s backward spelled inline, moved under a `linalg:` name so the seam
  can reach it (the `%la-im2col` / `%la-adam-step` precedent), in place over `z` with the
  widened add narrowed only on a single-float store; it also requires the two arrays to be
  the same width and `g`'s count to be `m * slab` (else the defun). `take-rows` copies
  slabs (`System.arraycopy` on the JVM, a `_v_get`/`_v_set` walk on wasm).
- **`%la-sum-squares (g acc)` and `%la-scale (g s)`** are `torch:clip-grad-norm`'s two
  element loops, which are the Adam precedent again: `(+ total (* v v))` as a LEFT fold in
  double from the caller's accumulator (so it is byte identity and NOT a lane reduction --
  a `linalg:sum` of squares would follow the reduction contract and move the clip
  scale), and `(* g s)` in place. A ratio accumulator or scale declines to the defun's
  exact rational arithmetic (on wasm `isNumber` excludes the ratio struct for the same
  reason; `unboxF64` would have converted it). `torch:clip-grad-norm` keeps its scalar
  branches and its in-place contract.

Per backend the touch points are the usual three and nothing new: `LinalgSimd`
(`define`s with the arities above) + `LinalgSimdKernels` (`compare*`, `where`,
`gatherStrided`, `takeRows`, `scatterRows`, `sumSquares`, `scale`; `bcastStrides` went
package-private for `where`), `JvmLinalgKernelCompiler.KERNELS` (`arity` 3 for `where` /
`%la-scatter-rows`, 5 for `%la-gather-strided`) + `JvmSimdVectorTemplate` (`laGreater` ..
`laEqual` through `laElementwise` with `OP_GT`..`OP_EQ`, `laWhere`, `laGatherStrided`,
`laTakeRows`, `laScatterRows`, `laSumSquares`, `laScale`), and
`WasmLinalgSimdCompiler` + `WasmLinalgSimdRuntimeBuilder` (`COMPARE_GT`..`COMPARE_EQ` 45..49,
`WHERE` 50, `GATHER_STRIDED` 51, `TAKE_ROWS` 52, `SCATTER_ROWS` 53, `SUM_SQUARES` 54,
`SCALE` 55; `FUNC_COUNT` 45 -> 56, `userFuncBase()` shifts by 111 under `--simd`; the
3-param members on `TYPE_CALLABLE_BASE + 2`, the 5-param one on `+ 4`, so again no new
type entry). The wasm builder grew two generic helpers for them: `emitOdometerN` (the
carry over ANY number of stride/offset pairs -- three for `where`; the old two-pair
`emitOdometer` delegates to it) and an `emitBcastShape` overload with an explicit decline
depth (the original hard-coded `br_if 2`, which is only right at the exit block's top
level; `where` folds the shape inside two `if`s and needs 4). `emitApplyBop` now branches
on `op >= BOP_GT` first, so the BCAST kernel answers the comparison masks at a broadcast
shape with a select chain over the op.

Measured on the GB10 (`.kb/gpu.md` carries the whole profile): the frames the round was
filed on -- `%LA-GATHER-STRIDED` 96 samples of 600, `WHERE` 20, `EMAP` 25, the backward
lambda 18, `CLIP-GRAD-NORM` 13 plus the `_dbl` / `_fvAset1` / `_fvAref1` boxing they
drove -- are 6 + 4 + 0 + 0 + 0 after it, and the `--gpu --simd` step moved 0.148 s ->
~0.11 s on that round alone (with the generator's device member, `.kb/gpu.md`, it is
0.119 s median over five rounds, 0.097 at best). The CPU-only `--simd` step moved
0.83 -> 0.80 s, because there the stacked product still dominates.

Verification: `LinalgSimdTest.theSelectsAndCopiesAre{InterceptedUnderSimd,
BitIdenticalToTheScalarOracleAtEveryShapeAndWidth}` and
`...DeclineWhatTheDefunSignalsAndSignalItUnchanged` (the dead-flag guard over all eleven
names, 40-odd value cases over every shape and width -- scalars on either side, broadcast
pairs, `-0.0`, a reversed slice, an empty index vector, mixed widths, a ratio
accumulator, boxed operands -- and a `torch:` program that drives them through
`masked-fill`, `index-select`, `cat`, `slice` and `clip-grad-norm` on one seeded tape; note
it uses `torch:mul` rather than `torch:matmul` between the tensors, because an f32 MATMUL
is the one member in that chain that is NOT byte-identical under `--simd`),
`JvmLinalgSimdAccelCompilerTest.theSelectsAndCopiesAre{ByteIdenticalToTheScalarPathAtEvery
ShapeAndWidth,EvaluateTheirArgumentsExactlyOnce}` (the same cases through `print`, plus
the three-temp and five-temp evaluate-once guards), and
`WasmLispCompilerIntegrationTest.wasmGcSimdLinalgSelectsAndCopiesAreByteIdenticalToThe
ScalarPath` (the same cases, one wasmtime run each). The four training examples print
byte-identical output on all four backends before and after (`train-gpt-soseki.lisp` and
`chapter02/section5.lisp`, `--simd` and `--gpu --simd`, the `--component` leg included).

## What is vectorized, and what is merely de-boxed

Not every member has a lane form worth writing. The interception is still worth it for all
forty-nine: it removes the per-element box allocation and the generic numeric dispatch that the
compiled defun pays, and on the interpreter it removes the whole tree-walking loop.

| member | interpreter / JVM | wasm-GC |
|---|---|---|
| `add`/`sub`/`mul`/`div`, array with array | lane loop | `gcMap2` lane loop |
| the same, array with a **double** scalar | lane loop | `gcBroadcastF64` lane loop |
| the same, array with a **single** scalar | scalar loop (see below) | `_v_get`/`_v_set` element loop |
| `sum`, `norm`, `dot` (v.v), `dot` (M.v = GEMV) | lane loop (reuses `VecSimdKernels`) | calls the `vec:` kernels |
| `dot` (v.M), `dot` (M.M) | `ikj` lane loop over the output row | `ikj` lane loop: shuffle-window b rows into a scratch row of the operand width, `_v_set` write-out (see below) |
| `outer` | lane loop over the row | whole destination groups when `m % lanes == 0`, else `_v_get`/`_v_set` |
| `amax`/`amin`/`argmax`/`argmin`/`trace` | scalar loop | `_v_get` element loop |
| `sqrt`/`abs`/`negative` (unary, todo 109) | lane loop | `gcMap1` lane loop (defun-mirroring forms) |
| `maximum`/`minimum`, array with array | scalar select loop (perf-only choice; bits identical either way) | `gcMap2Select` gt/lt mask + bitselect lane loop, BOTH widths |
| the same, array with a **double** scalar | scalar select loop | `gcBroadcastSelectF64` lane select (save/restore bracket kept: a select over padding can answer s) |
| the same, array with a **single** scalar | scalar loop widened vs the full double | `_v_get`/`_v_set` element loop widened vs the full double |
| `exp`/`log`/`tanh`/`sin`/`cos`/`tan`/`asin`/`acos`/`atan`/`sinh`/`cosh`/`sign` (unary, todo 109) | de-boxed scalar loop (the same `java.lang.Math` call) | `_v_get`/`_v_set` element loop emitting the defun's f64 sequence |
| `transpose` | scalar loop | lanes x lanes register-block shuffles when BOTH dims are lane-aligned, else `_v_get`/`_v_set` |
| `reshape` | `Arrays.copyOf` | whole lane-group copy |
| same-width broadcast pair (any op) | scalar odometer walk | `BCAST` `_v_get`/`_v_set` odometer walk (op as an i31 selector) |
| `transpose` with axes | scalar odometer copy | `TRANSPOSE_AXES` `_v_get`/`_v_set` odometer copy |
| `sum`/`amax`/`amin`/`argmax`/`argmin` with axis | scalar fold loops | `*_AXIS` `_v_get`/`_v_set` fold loops |
| `%la-matmul-nd` (stacked product) | the `dot` M.M lane loop per batch offset | the `MATMUL_ND` kernel: `dot`'s `emitGemmRow` per batch offset |
| `erf` (the A&S 7.1.6 series) | de-boxed scalar loop | `ERF` `_v_get`/`_v_set` element loop, series inline |
| `%la-rng-fill` (rand / randn / uniform) | de-boxed scalar loop, states in `int` locals | `RNG_FILL` `_v_set` element loop, states in i32 locals |
| `%la-adam-step` (the fused Adam update) | de-boxed scalar loop over four arrays | `ADAM_STEP` `_v_get`/`_v_set` element loop |

The wasm-GC lane forms for `dot` (v.M / M.M) / `outer` / `transpose` shipped as the todo-107
follow-up (2026-07-10). The GEMM loop reads each `b` row through the same `i8x16.shuffle`
window as `vec:matvec` (`WasmVecSimdRuntimeBuilder.emitRowGroup`, promoted package-private)
and multiply-accumulates whole groups into a **scratch row of the operand width**
(`_v_new(p, kind)`, reused and re-zeroed per output row), written out element-wise through
`_v_set` -- O(n·p) writes against O(n·m·p) flops. One loop serves both widths: the group
count is the scratch row's own and every window group maps to the accumulator group of the
same index, so the only per-width difference is `f32x4`/`f64x2` and the splat of `a[i][k]`
(demoted through `f32.demote_f64` at `#f` width, back to the width it was stored at). The
window's overhang past a row's end reads REAL next-row elements (unlike matvec's zero-padded
x) but lands only in accumulator lanes past `p`, which the write-out never reads.

An `#f` scratch row is what makes the matrix product a reduction-contract kernel (below)
rather than a bit-identical one, and all three `--simd` backends had to move together or
they would stop agreeing. wasm accumulated in f64 until then, widening each window group
through `f64x2.promote_low_f32x4` into two accumulator groups; that instruction is no longer
emitted by anything (its `WasmTreeShaker.skipSimd` case stays, harmless). `transpose` uses two shuffles per 2x2 f64 block and the
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
- **The matrix product follows the reduction contract as well.** `dot` (v.M) and `dot` (M.M)
  accumulate in the OPERAND width: an `#f` product folds each output cell over `k` in f32,
  in the oracle's own ascending order, so it is close to the scalar defun but not equal to
  it. The defun cannot follow -- rontolisp has one float type and it is f64 (`LispNames`:
  "Every float shares the one double") -- so there is no version of this in which the oracle
  and the kernel agree, exactly as for `sum`/`dot`/GEMV. Measured worst case against the
  oracle on zero-mean random operands: max ~3-4% RELATIVE error, always on a cell whose true
  value has cancelled to near zero; that is what every f32 GEMM in the industry does,
  PyTorch's CPU `sgemm` included. **Which lanes ran cannot move the answer**: the lanes go
  across the output row (the `j` axis), which carries no summation, so the lane loop, the
  scalar tail and the wasm f32x4 loop fold every cell identically, and the three `--simd`
  backends agree bit for bit. At `#d` width nothing changed: still bit-identical.
- **Why not keep the f64 accumulator, which WAS bit-identical?** Because it forbids lanes:
  an f64 accumulator can only be fed by widening each f32 lane group through
  `FloatVector.convert(F2D)`, and where that intrinsic is missing the widening is
  catastrophic. `.todo/123-gpu-acceleration/MatmulFProbe.java` measures all four kernels
  head to head and is rerunnable; **it answers differently per architecture, so rerun it
  before quoting it**:

  | n=512, ms/call | scalar + f64 acc (the old kernel) | F2D lanes + f64 acc (wasm's old way) | f32 lanes + f32 acc (today) | the f64 kernel |
  |---|---|---|---|---|
  | aarch64 / NEON, NVIDIA GB10 Grace (f32 4 lanes, f64 2) | 39.0 | **7477** | **10.4** | 19.5 |
  | aarch64 / NEON, Apple M4 Max (f32 4 lanes, f64 2) | 35.9 | **4474** | **9.7** | 18.1 |
  | x86-64 / AVX (f32 8 lanes, f64 4) | 33.3 | 52.1 | **22.8** | 47.1 |

  On NEON `convert(F2D)` has no intrinsic at all -- 125-190x slower than the scalar loop it
  would replace -- and the scalar f64-accumulator kernel left `#f` matmul **2x slower than
  `#d`**, the one member where the narrower width lost. On x86-64 the conversion IS
  intrinsified and the old kernel was already ahead of `#d`, so the symptom does not
  reproduce there. What holds on BOTH is the ranking: f32 lanes with an f32 accumulator
  wins, and the F2D widening never does. Do not port it to the JVM, and do not spend time
  on `convertShape` variants.

  At the rontolisp level (x86-64, warm, 20 reps, ms/call) `#f` is now the faster width on
  both scalar backends, which is what a narrower element type is for:

  | `linalg:matmul` | interpreter `#d` | interpreter `#f` | JVM `#d` | JVM `#f` |
  |---|---|---|---|---|
  | n=256 | 8.00 | **4.90** | 7.75 | **4.30** |
  | n=512 | 51.55 | **25.75** | 53.45 | **28.85** |
- **`trace`, `amax`, `amin`, `argmax`, `argmin`** are bit-identical: they read elements
  widened to `double`, exactly as the defun does.
- **`erf` is bit-identical at both widths**, and by the same rule: `%la-erf-1`'s series
  runs in `double` and the result narrows only on a single-float store, which is what the
  `emap` defun does. It is NOT a reduction and does not follow the `#f` reduction contract
  -- accumulating the series in single would break the identity.
- **The declined-shape follow-up kernels are ALL bit-identical at both widths.** The
  broadcast and the axes transpose read widened, compute in double and narrow only on a
  single-float store (`%la-bcast-loop`'s own rule; the transpose is a pure copy). The
  AXIS folds do NOT follow the lane-reduction contract: `(linalg:sum a :axis 0)` accumulates
  in f64 from the defun's `0` seed in the defun's own order (an axis fold is a scalar
  loop, not a lane reduction -- only the no-axis `sum`/`dot`/`matvec` lanes reduce), and
  the `amax`/`amin` folds mirror `(if (> x acc) x acc)` -- the ACCUMULATOR wins
  ties/NaN, the opposite of the element-wise select, so `(linalg:amax #d((-0.0 0.0)) :axis 1)`
  is `#d(-0.0)` while `(linalg:maximum #d(-0.0) #d(0.0))` is `#d(0.0)`. A vector
  reduced without keepdims returns the boxed f64 accumulator itself (never narrowed,
  even for an `#f` input), and the axis `argmax`/`argmin` results are packed DOUBLE
  arrays at any input width -- both exactly as the defuns answer.
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
| `(round (aref (linalg:dot v (reshape v '(1024 1))) 0))` -- **v.M** | 16778240 | **16777216** |
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
differed per backend. **todo-108 fixed the scalar comparisons on all three backends**
(interpreter `compareNumeric` gained an UNORDERED state; the JVM literal path picks
DCMPG/DCMPL per operator and the runtime path uses the `_cmpb` bitmask; wasm's variable
path funnels through `_rat_cmp_bits`), and `LinalgSimdKernels` switched from
`Double.compare` to plain Java `>`/`<` in the same change -- the lockstep the old version
of this section demanded. All three kernels now compare IEEE, all three match their own
scalar defun (pinned by `LinalgSimdTest`'s `-0.0` oracle-match cases), and the defuns
match each other: `(linalg:amax #d(-0.0 0.0))` is `-0.0` everywhere (first-element tie
win, since IEEE `>` is false on a `0.0`/`-0.0` tie). `-0.0` / NaN comparison cases are
allowed in `ci-spec.yaml` now; the float-edge cases there pin the convergence.

Two edges were deliberately left diverging there (CL permits either, and no kernel reads
them), so they are the exception to the sentence above -- keep such forms out of
`ci-spec.yaml`:

- **Variable-path `min`/`max`**, off the double-literal fast path: the JVM `_min`/`_max`
  are a `buildSelect` over `_cmp` (not `Math.min`) and wasm's are a `_rat_cmp` select, so
  NaN does not propagate and a +/-0.0 tie picks by position -- unlike the literal path,
  which is `Math.min`/`Math.max`.
- **`eql`/`equal` on `-0.0` vs `0.0`** is unaudited across backends (CLHS makes them `=`
  but not `eql`).

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
`la*` method refs (one per intercepted member) under **package-prefixed keys**
(`"linalg:add"`; the internal pair under its double-colon spelling
`"linalg::%la-im2col"`), because `vec:add` and `linalg:add` share a member name. The
descriptors are composed per member arity (1/2/5/6 `Object` params).

**`jdk.incubator.vector` is an optional module**: `_simdInit`'s
`Lookup.defineClass` resolves the template's verifier-visible types AT THAT CALL, so a JVM
without `--add-modules jdk.incubator.vector` fails to LINK the bridge -- before any bridge
method ever runs, and unlike `--blas`/`--gpu`, whose "is it there" probe is a method call
inside an already-linked bridge. `_simdInit` catches the `LinkageError`, prints the same
one-line warning `RontoLispCli.enableSimd` prints (once -- the `_simdInited` guard field
also gates the catch, so a program with a thousand accelerated call sites still warns
once), and leaves the new `_simdAvailable` field false; `_simdReady()` exposes it as one
more `ops` entry (`"available"`). Every accelerated call site -- `JvmSimdCompiler`'s `vec:`
call sites, `JvmSimdCompiler.compileGpuMatvec`'s `--gpu`-declined rung, and the `--simd`
rung below -- checks `_simdReady()` BEFORE emitting a call that would resolve a method
reference into the bridge, and falls back to the scalar defun (over the SAME temps) when it
reads false, instead of letting the class-define failure surface as a raw
`NoClassDefFoundError` at whichever call site runs first. `JvmSimdModuleFallbackTest` pins
this by running a compiled class in a fresh child JVM that never sees `--add-modules`.

The compiled packed array carries an **in-array header** `[rank, dim..., data...]`,
`off = 1 + rank`. So an element-wise linalg kernel is the `vec:` one at a different offset,
and the fresh result must copy the whole header (`laNewLike`) rather than write `[1, n]`.

`JvmLinalgKernelCompiler.compile` emits:

```
_simdInit(); a = <arg1>; b = <arg2>;          // ASTORE into temps
if (_simdReady()) {                           // skipped entirely if the module is absent
  r = Bridge.laAdd(a, b);
  if (r != null) goto end;
}
r = linalg$colonadd(a, b);                    // ALOAD the same temps
end:
```

The gate is `JvmLispCompiler.programUsesAnyAcceleratedSimdOp`, now scanning both packages.
Note it scans the program AFTER `LinalgLibrary.process` has spliced the defuns, so ANY
linalg program embeds the bridge (the spliced `linalg.lisp` itself contains the accelerated
call sites) -- exactly as any `vec:` program does. `(print (+ 1 2))` does not.

### wasm-GC

Fifty-six standalone functions at `WasmLispCompiler.linalgFuncBase()` = `FUNC_VEC_BASE
+ 55` (the vec: block is 55 with the todo-109 kernels and `-into` siblings), emitted only
under `--simd`; `userFuncBase()` now shifts by 111. The LAST eleven are the 2026-08-22
selects and copies (`COMPARE_GT` .. `SCALE`, indices 45..55, above); before them
todo-473's `RNG_FILL`
(index 43) and `ADAM_STEP` (index 44), both on the always-present five-eq-param
`TYPE_CALLABLE_BASE + 4` type `IM2COL` already used, so again no new type entry; before
them todo-468's `ERF` (index 42,
the always-present one-eq-param `TYPE_CALLABLE_BASE` type), before it todo-467's
`MATMUL_ND` (index 41, an ordinary two-eq-param type, so no new type entry); the seven
before THAT are
the declined-shape follow-up helpers (`BCAST` .. `ARGMIN_AXIS`); `BCAST` takes its op as an i31 (the
3-eq-param `TYPE_CALLABLE_BASE + 2` type, always present) and is called from the six
element-wise kernels' unequal-dims branch, the others from the extended call sites. The
odometer/fold scratch (dims copies, strides, counters, permutations) lives in fresh
`$hash_buckets` i31 arrays -- kernels cannot hold extra typed local groups beyond the
fixed `withLocals` order, and rank-sized arrays are allocation-trivial next to the walk
itself. `WasmLispCompilerTest.simd
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

- `eval/LinalgSimdTest` (48) -- interception guard (`#'linalg:add` is `#<function
  linalg:add>` under `--simd`, `#<lambda>` without; `emap`/`inv`/`det`/`solve`/`array-equal`/
  `mean`/`matmul`/`flatten` stay `#<lambda>`), byte-identity vs the oracle at both widths and
  both ranks, scalar broadcast on both sides, the declined inputs, the f32 probe, and the
  declined-shape follow-up (broadcast pairs, transpose axes, axis folds incl. the strict
  tie/seed semantics and the declined axis inputs). `erfMatchesTheScalarOracleOverTheWhole
  RangeAtBothWidths` covers the `|x| >= 6` cutoff on both sides, `0.0`/`-0.0`, negatives,
  the `|x| ~ 3` region and the exact `torch:gelu` riding on it. The JVM suite has the
  twin minus the gelu line: only the interpreter's harness has `torch.lisp` loaded.
  `theAdamStepAndTheGeneratorFillAreInterceptedUnderSimd` is todo-473's dead-flag guard,
  and its value cases run four Adam steps over four aligned arrays at both widths and all
  three decay modes (plus the optimizers themselves, which only this harness can reach),
  and every `rand`/`randn`/`uniform`/`choice`/`permutation` draw from one seed interleaved
  with a bare `%la-rng-next`. Note the long-fill case compares the ARRAY, not a
  `linalg:sum` of it: `sum` is itself a lane reduction under `--simd` and follows the
  reduction contract, not byte identity.
- `codegen/jvm/JvmLinalgSimdAccelCompilerTest` (34) -- the bridge-embedded dead-flag guard,
  the same byte-identity set, the evaluate-once guards (base AND extended call sites), the
  library errors still signalling, the axis/broadcast/transpose-axes shapes.
- `codegen/wasm/WasmLispCompilerIntegrationTest` (Docker + wasmtime), eleven cases (the
  ninth is `...MatmulNdIsByteIdenticalToTheScalarPath`, every batch shape the odometer
  walks plus the three declines; the tenth is `...ErfIsByteIdenticalToTheScalarPath`, both
  sides of the `|x| >= 6` cutoff, `-0.0`, the `|x| ~ 3` region, both widths, rank 2 and
  the boxed declines; the eleventh is todo-473's
  `...OptimizerAndGeneratorAreByteIdenticalToTheScalarPath`, the Adam step at both widths
  and all three modes plus its five declines, and every generator rule plus its four):
  `wasmGcSimdLinalg{ElementWiseAndShapeKernels,ReductionsAndProducts}AreByteIdenticalToThe
  ScalarPath`, `...LaneProductsMatchTheScalarPathAtEveryRowLaneOffset` (the GEMM / outer /
  transpose lane forms: every shuffle-offset variant via a 7-column `#f` matrix, the odd-`p`
  sentinel-group write, aligned vs unaligned outer/transpose, a next-row inf inside the
  window overhang), `...DeclinedInputsRunTheScalarDefun`,
  `...SingleFloatReductionsAccumulateInSinglePrecision`, `...ComposesWithOptimize`,
  `...AxisFormsRunTheAxisKernelsAndMatchTheScalarPath`,
  `...BroadcastAndTransposeAxesMatchTheScalarPath`, `...ErfIsByteIdenticalToTheScalar
  Path` (that harness splices only `vec.lisp` / `linalg.lisp`, so the `torch:gelu` leg is
  covered by hand and by the other two suites),
  `...OptimizerAndGeneratorAreByteIdenticalToTheScalarPath`.
- `WasmLispCompilerTest.simdAppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks`.
- `ci-spec.yaml` never passes `--simd`, so the cross-backend E2E is unaffected. The component
  leg (`--component --simd`) and `--optimize` were verified by hand and by the integration
  test.
- `examples/ml/{tiny-llm,linear-regression,heat3d,deep-digits}.lisp` print byte-identical
  output with and without `--simd` on all three backends. (`nn-vec.lisp` has a random init,
  so only its headings are stable -- `examples.yaml` checks only those.)
- `examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` prints byte-identical output
  with and without `--simd` on ALL FOUR backends (re-verified for todo-467 and again for
  todo-468, the `--component` leg included), and `examples/ml/tiny-llm.lisp` on all three (its one
  elapsed-time line aside -- interpreter 7061 -> 96 ms with the flag).
- For todo-473, the same program at the NOTEBOOK's shapes (40 training steps, so the
  optimizer and the dropout masks really run) prints byte-identical output before and
  after the change on both a `--simd` and a `--gpu --simd` JVM class -- which is the
  acceptance that matters for two members whose kernels write their operands IN PLACE.

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
