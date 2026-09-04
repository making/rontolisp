# `linalg:` kernel interception (`--simd`)

Read `.kb/vec.md` first: this is "do what `vec:` does, for `linalg:`", reusing the `vec:` lane
loops rather than copying them. `.kb/linalg.md` has the semantics of the library accelerated.
User-facing description: `doc/{en,ja}/guides/simd-acceleration.md` — keep the intercepted set,
the declined-input fallback and the precision contract in sync with its "Accelerating linalg"
section; the JVM-specific measurements stay here.

| backend | interceptor | kernels | without `jdk.incubator.vector` |
|---|---|---|---|
| interpreter (`prog.lisp --simd`) | `eval/LinalgSimd` (re-`defineFunction`) | `eval/LinalgSimdKernels` | `VecSimd.available()` probes first; `RontoLispCli.enableSimd` warns once and leaves `evaluator.setSimd` off, so `LinalgSimd.install` never runs |
| JVM (`-o Prog.class --simd`) | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmSimdVectorTemplate.la*` (one embedded bridge) | `_simdInit` catches the `LinkageError` from `Lookup.defineClass`, warns once, leaves `_simdAvailable` false; every call site checks `_simdReady()` BEFORE resolving a reference into the bridge |
| wasm-GC (`-o prog.wasm --simd`) | `codegen/wasm/WasmLinalgSimdCompiler` (call site) | `WasmLinalgSimdRuntimeBuilder` (56 emitted functions) | n/a — kernels are emitted wasm functions |

`--no-gc` is out of scope: `linalg:` cannot compile there at all (`linalg::%la-make` uses
`&optional`, and `--no-gc` has no general array type).

## The flags stacked on this seam

- **`--blas`** (`.kb/linalg-blas.md`) and **`--gpu`** (`.kb/gpu.md`) are a SECOND and THIRD
  flag over the same seam, interpreter + JVM only (both call out through the FFI). They put a
  tuned CBLAS `gemm`, and a device product ahead of that, in front of the lane kernel for
  `linalg:dot`. `--gpu` also takes `%la-matmul-nd`, the twelve element-wise members whose
  scalar cost is a libm call (`exp` `log` `tanh` `sin` `cos` `tan` `asin` `acos` `atan` `sinh`
  `cosh` `erf`), and the ten members whose CPU kernel BELOW is a scalar odometer walk rather
  than a lane loop (`add` `sub` `mul` `div` `maximum` `minimum` at a BROADCAST shape, `sum`
  `amax` `amin` with `:axis`, `transpose` with an axes list), where it has no `--blas`
  neighbour. **That last set is chosen against the CPU column in THIS file**: the same
  `linalg:sub` is a device member against a `(4 256 1)` operand and a decline against a
  same-shaped one. Hence the JVM interceptor is `JvmLinalgKernelCompiler`, not
  `JvmLinalgSimdCompiler` — it emits a CHAIN of up to three attempts over one set of temps,
  ending at the scalar defun, at the EXTENDED (option-form) call sites too. With `--blas` and
  `--gpu` off, a `--simd` build emits and computes exactly what it did before.
- **`--parallel`** (`.kb/simd-parallel.md`) is a modifier of `--simd`, not a rung: the matrix
  products (`dot`'s matrix cases and `%la-matmul-nd`) run a row range per thread and stay
  bit-identical.
- **`--gpu` is the one flag whose element-wise results are NOT bit-identical to the defun** —
  for its TRANSCENDENTAL tier only (the device has its own libm and at `#f` evaluates AT the
  operand width where these kernels evaluate in double and narrow). Its strided tier
  (broadcast pairs, axis folds, axes transpose) is bit-identical at both widths.
  `.kb/gpu.md` carries the per-member divergence table and its tolerance. A call the device
  declines is still the lane kernel's and still bit-identical, but a `--gpu` run is no longer
  byte-comparable with a `--simd` one.

## Why: `--simd` used to make `linalg:` SLOWER on wasm-GC

`--simd` switches the packed float-array representation to a `TYPE_VBLOCK` over an
`(array (mut v128))` of lane groups. Every *scalar* element read/write then goes through
`_v_get` / `_v_set` — an `array.get` plus an immediate-lane `if`-chain, and for a write a
whole-group read-modify-write. `vec:` kernels are intercepted at their call sites and never
pay it; no `linalg:` function was, so linalg paid the new representation's cost and got
nothing back — `--simd` made `linalg:add`/`linalg:dot` ~10% SLOWER on wasm-GC than scalar.
Intercepted, they now match `vec:` exactly. (Do not size the JVM win from a short run — a
60-rep program is all JIT warm-up. The interpreter's win is the INTERCEPTION, not the lanes:
the defun it replaces is a boxed `do` loop with a `funcall` of `#'+` per element.)

## The declined-input protocol — the one structural difference from `vec:`

`vec:` kernels accept packed float arrays and nothing else. `linalg:` defuns also accept
general (boxed) arrays, **mixed widths** (NOT an error here: the defun widens both operands and
keeps the FIRST one's width), a scalar operand on either side, two plain numbers, and arrays of
DIFFERENT shapes (broadcast by the numpy rules, `%la-bcast-loop`; the shape-mismatch `error`
only when no broadcast fits). A SAME-width broadcast pair IS handled by the kernels (the
element-wise kernels' unequal-dims branch runs the general numpy odometer walk —
`bcast`/`laBcastDD`/the wasm `BCAST` helper); a MIXED-width or incompatible pair declines.

**Every kernel is a partial function: it answers "declined" for an input it does not handle,
and the call site then runs the scalar `linalg.lisp` defun.** The library stays the single
source of truth for every edge case, error messages included.

The sentinel is a **null reference / Java `null`**, which works only because compiled nil is
`ACONST_NULL` / a wasm null ref AND none of the intercepted members ever returns nil.
`linalg:array-equal`, which does return nil, is therefore deliberately NOT intercepted (it also
has a per-element `numberp` check). **Anything that would need a second sentinel does not get
intercepted.**

- **The compilers must evaluate each argument form exactly once, into a temp**, because both
  the kernel and the fallback read it. Pinned by
  `anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines` (JVM) and
  `wasmGcSimdLinalgDeclinedInputsRunTheScalarDefun` (wasm).
- **The tree shakers need no new root**: the call site has an ordinary call edge to both the
  kernel and the scalar defun.
- **An intercepted member may be VARIADIC**, and six have EXTENDED (option-form) call sites:
  `transpose` with a positional axes list (`laTransposeAxes` / `TRANSPOSE_AXES`),
  `sum`/`amax`/`amin` with `:axis`/`:keepdims` (`laSumAxis` &c / `SUM_AXIS` &c),
  `argmax`/`argmin` with `:axis`. Kernels stay POSITIONAL (`laSumAxis(a, axis, keepdims)`);
  `compiler.LinalgKernelCallLayout.layout` — shared by both codegens so they pattern-match
  identically — maps the call's argument forms onto the kernel parameters: literal
  `:keyword value` pairs over the member's declared keywords, each at most once, in any order,
  a missing option padded with null = nil. Anything else (a non-literal keyword, an unknown or
  repeated one, an odd tail, a positional count beyond the shape) is NOT a kernel call and
  routes to the ordinary direct-call path, where the defun's `&key` prologue signals. The
  decline branches package the rest list from the SAME temps — keyword literals included: the
  base arity pushes an EMPTY rest (`ACONST_NULL` / `ref.null eq`), an extended call links the
  surplus temps into a cons chain (`Object[2]` cells / `struct.new $cons`). Both compilers also
  verify the defun's required count still matches the base arity (and, for an extended call,
  that the defun is variadic) and bail to `compileDefault` otherwise. The interpreter needs the
  arity-RANGE guard in `LinalgSimd.define` (1..5 for sum/amax/amin, 1..3 for argmax/argmin)
  plus `LinalgSimd.options`, the runtime twin of the layout; a declined call falls through to
  `applyGlobal`. Pinned by `axisFormsRunTheAxisKernelsAndMatchTheScalarReference` +
  `anAxisArgumentFormIsEvaluatedExactlyOnceEvenWhenTheExtendedKernelDeclines` (JVM),
  `wasmGcSimdLinalgAxisFormsRunTheAxisKernelsAndMatchTheScalarPath` (wasm),
  `axisFormsRunTheFoldKernelsAndMatchTheScalarOracle` (interpreter).

## The intercepted set (49 members)

`add` `sub` `mul` `div` `sum` `norm` `amax` `amin` `argmax` `argmin` `trace` `transpose`
`reshape` `dot` `outer`; the unary ufuncs `exp` `log` `tanh` `sin` `cos` `tan` `asin` `acos`
`atan` `sinh` `cosh` `sqrt` `abs` `negative` `sign` (named emaps — see `.kb/vec.md` for the
per-backend lane-vs-element-loop decisions and the wasm defun-edge mirroring); the comparison
selects `maximum` `minimum` (strict `(if (> x y) x y)` / `(if (< x y) x y)` selects, never
Math.max or a lane min/max — the SECOND operand wins any false comparison, ties and NaN
included, and unlike the transcendentals these are CROSS-BACKEND-identical); the comparison
masks `greater` `greater-equal` `less` `less-equal` `equal` (arity 2), `where` (arity 3),
`take-rows` (arity 2); and the INTERNAL members `%la-im2col` (5) / `%la-col2im` (6),
`%la-matmul-nd` (2), `erf` (1), `%la-rng-fill` (5), `%la-adam-step` (5),
`%la-gather-strided` (5), `%la-scatter-rows` (3), `%la-sum-squares` (2), `%la-scale` (2).

A `%`-prefixed member's canonical qualified spelling carries the DOUBLE colon
(`linalg::%la-im2col`), which is how the interpreter's function binding, the compilers'
`ctx.functions` keys and the emit-gate symbol scan must compose it —
`Jvm/WasmLinalgSimdCompiler.qualifiedName` and `LinalgSimd.define` all branch on the `%`
prefix.

`%la-im2col`/`%la-col2im` (the CNN window unfolding) are pure index-arithmetic loops, so
bit-identical at both widths (col2im accumulates two same-width elements per store, which at
f32 IS the defun's widen-add-narrow round trip). They decline anything but a rank-4 packed
operand (col2im: any-rank packed col + a literal 4-list of dims whose product the col size must
match exactly) with i31/integer window parameters — positive filter/stride, non-negative pad,
both padded extents `h + 2*pad - fh` / `w + 2*pad - fw` non-negative, so the defun's `floor` is
a plain truncating division. JVM bridge descriptors are built per arity (5/6 `Object` params);
the wasm kernels use the always-present `TYPE_CALLABLE_BASE + 4` / `+ 5` types.

**The declined-shape follow-up** closed the three shapes that still ran boxed defuns: the
general numpy BROADCAST between two same-width packed arrays (from the element-wise kernels'
unequal-dims branch, `maximum`/`minimum` included), `transpose`'s rank-n AXES form, and the
AXIS forms of `sum`/`amax`/`amin` (axis + keepdims) and `argmax`/`argmin` (axis). What grew is
the set of intercepted CALL SHAPES and the wasm function block (+7: `BCAST`, `TRANSPOSE_AXES`,
`SUM_AXIS`, `AMAX_AXIS`, `AMIN_AXIS`, `ARGMAX_AXIS`, `ARGMIN_AXIS`). All are deliberately
SCALAR walks (odometer copies and folds, `_v_get`/`_v_set` element loops on wasm): the win is
de-boxing, and scalar double arithmetic is what makes them bit-identical. **Being scalar walks
is also what made them `--gpu`'s phase-3 member set** (`.kb/gpu.md`). If a lane form is ever
written for the row-broadcast case — `(n m p) op (n m 1)` is a splat per row and the single
hottest kernel in a `--gpu --simd` training step — rerun the GPU item's `shaped-baseline.lisp` /
`StridedCrossover.java`, because it would move that member's crossover. Declines: nil /
non-integer / out-of-range axis, an empty axis (the vector `sum` of an empty axis would need
the defun's INTEGER 0), a non-permutation axes list, a mixed-width or non-broadcastable pair,
any general boxed operand.

Accelerated **transitively**, so not intercepted directly: `rand`/`randn`/`uniform` (all call
`%la-rng-fill`), `torch:step` over `torch:adam`/`torch:adamw` (calls `%la-adam-step` per
parameter), `mean` (calls `sum`), `matmul` at every rank (rank <= 2 -> `dot`, rank >= 3 ->
`%la-matmul-nd`), `flatten` (`reshape`), `solve` (`inv` then `dot`), `square` (`mul`),
`reciprocal` (`div`), `clip` (`maximum` then `minimum`), `relu` (`maximum` with 0.0).

**Never** intercepted: `emap` (arbitrary Lisp callback), `det` / `inv` / `solve`'s pivoting
elimination (data-dependent pivots, sequential column dependency), `array-equal` (the
nil-return sentinel collision), and the constructors.

`#'linalg:dot` still names the scalar defun on the compiled backends — the interception is at
the *call site* there, while the interpreter overrides the *function binding*. So a `linalg:`
function passed to `funcall`/`mapcar` is not accelerated when compiled. Same as `vec:`,
deliberately.

### The stacked matrix product (`%la-matmul-nd`)

`linalg:matmul` routes rank <= 2 to `dot` and rank >= 3 to `linalg::%la-matmul-nd` —
`torch.bmm`, hence every attention layer and every `torch:linear` over a `(B T C)` activation.
Before interception it was a boxed `outer x M x K x N` walk built from no intercepted member,
so `--simd` did nothing on interpreter/JVM and made it ~11% SLOWER on wasm-GC.

**The kernel is `dot`'s M.M lane loop, once per batch offset** — not a second lane loop. All
three backends factored the rank-2 product into an offset-taking slab
(`LinalgSimdKernels.matmulInto`, `JvmSimdVectorTemplate.laMatmulInto`, and on wasm the shared
`emitGemmRow` over a scratch accumulator row) and the batched kernel calls it per batch.
Offsets come from the `%la-batch-strides` odometer, which is `%la-bcast-strides` scaled by the
trailing matrix size — so a BROADCAST leading axis has stride 0 and needs no special case (on
wasm that scaling is the `baseLocal` parameter `emitAlignedStrides` gained; `emitBcastShape`
was factored out of `buildBcast` so the batch shape reuses the element-wise broadcast rule over
the leading axes only).

Declines, all answered by the defun: a general boxed operand, mixed widths, a RANK-1 operand on
either side (the numpy promote-then-drop-the-axis rule), a non-broadcastable batch shape, a
mismatched inner dimension, any empty extent (a zero-length `k` fold needs the defun's INTEGER
`0` seed).

**Precision is a per-batch `linalg:dot`, not the defun**: each cell folds `k` in the defun's
ascending order at the OPERAND width, so `#d` is bit-identical and `#f` follows the reduction
contract. Pinned by the rank-3 line of the f32 probe in all three suites:
`(linalg:matmul (reshape v '(1 1 1024)) (reshape v '(1 1024 1)))` prints **16777216** on every
`--simd` backend against the scalar 16778240.

**`--gpu` intercepts this member too**, and the batch odometer is where they differ: the device
walks the batch with ONE stride per operand, so a broadcast leading axis is stride 0 as here,
but a broadcast axis sitting UNDER a non-broadcast one is a decline there and lands back on
this kernel. Its threshold is the TOTAL work `batch*n*m*p`, since a stack is one round trip.

Two facts to keep: the wasm-GC rank-3 shape **stopped being slower than scalar**, and `#f` is
now the FASTER width on the batched shape everywhere (the ranking was inverted for exactly as
long as the member was un-intercepted, because the boxed walk widened on every read and
narrowed on every store).

### The error function (`erf`)

`linalg:erf` is `(linalg:emap (function linalg::%la-erf-1) a)`, and `emap` is **never**
intercepted — so erf was the one activation primitive that got nothing from the flag while
`torch:gelu`'s DEFAULT (`:approximate :none`, matching `nn.GELU`) is built on it. Intercepting
the MEMBER was therefore necessary.

**The kernel is `%la-erf-1`'s own arithmetic in the defun's order, bit-identical at both
widths**: the `|x| >= 6` short circuit, the all-positive-term A&S 7.1.6 series
`term = term * 2x^2 / (2n+1)` broken at `term < 1e-17 * total` and capped at `n = 200`, then
`1.1283791670955126 * |x| * exp(-x^2) * total` with the sign applied last. It computes in
DOUBLE at both widths and narrows only on the store (`emap`'s rule); accumulating the series in
single would contradict the `#f` REDUCTION contract. Declines anything but a same-width packed
`#d`/`#f` operand.

`exp` is still per-backend, mirroring each backend's own compiled defun (`Math.exp` on
interpreter/JVM, `WasmExpCompiler`'s Horner approximation via `emitExpF64` on wasm), so
`(linalg:erf #d(-1.0))` differs in the last two digits there. `abs` and unary minus USED to be
per-backend too — with no double literal among their argument forms they took wasm's generic
path (`abs` as `_rat_cmp`'s float compare, leaving `-0.0` alone where `Math.abs` folds it to
`0.0`; `(- v)` as `_rat_sub(0, v)`, `+0.0` for `+0.0` where IEEE negation gives `-0.0`). Both
wasm emitters now take `f64.abs` / `f64.neg` on the branch that has established a float
operand, and the kernel is spelled the same way; pinned by ci-spec's `signed-zero` case.

**No lane form, and the reason is not "no v128 instruction".** The per-element iteration count
is DATA-DEPENDENT (it grows with `x^2`), so a lane loop must run every lane to its group's
maximum and mask the broken ones. Measured on aarch64/NEON (2 f64 lanes) before writing one: a
lane `exp` (`VectorOperators.EXP`) is 14x SLOWER than the scalar de-boxed loop AND not
bit-identical to `Math.exp`; scalar `Math.exp` per lane is a **1.20x** ceiling, and only once
JIT-warm. INTERCEPTION alone buys 132x on the interpreter and 13x on wasm-GC. **Do not spend
time on a masked lane form**; if revisited, measure on a machine with 4+ f64 lanes first. The
JVM gains only 1.26x here because the compiled defun's boxing is small next to the series,
which is division-latency bound (~15 dependent f64 divides per element).

### The optimizer update and the generator (`%la-adam-step` / `%la-rng-fill`)

Both are here because **the seam intercepts `linalg:` names and nothing else**, and a JFR
profile of `train-gpt-soseki.lisp` under `--gpu --simd` said the remaining cost was two boxed
Lisp loops that were not `linalg:` at all: `torch::%o-adam-step` at 22-31% and
`linalg:rand`/`randn` at 16%, with `_dbl` and `_fvAset1` on top. **So the loops moved to
`linalg:` rather than the seam widening to `torch:`** — no new touch point in any backend, one
new intercepted member each; `%la-im2col` is the precedent. Rewriting Adam over whole-array
intercepted members was rejected without measurement: a dozen whole-array temporaries per
parameter per step against a fused loop with none, and a fresh array per parameter per step is
already the allocation that dominates a small training loop (`.kb/torch.md`).

**`linalg::%la-adam-step (x g m v ps)`** is the defun's inner loop over four aligned same-width
arrays, with the whole rule in an eleven-element packed double vector: `lr`, `lr*wd`, `wd`,
`b1`, `1-b1`, `b2`, `1-b2`, `eps`, `c1`, `c2`, `mode`. Two load-bearing details:

- **`mode` replaced the two booleans.** 0 = no weight decay, 1 = COUPLED (`torch.optim.Adam`:
  the L2 term rides the gradient), 2 = DECOUPLED (`torch.optim.AdamW`: the parameter shrinks on
  its own). `wd = 0` collapses both spellings to the same arithmetic, so three values cover
  four combinations.
- **`lr * wd` is multiplied by the CALLER**, while both may still be exact rationals.
  `(* lr wd x)` is a left fold, so the scalar rule formed `(double)(lr*wd) * x`; storing them
  separately and multiplying in the kernel would round twice and move a run with `:lr 1/100`.

Bit-identical at both widths: every element is read widened to double, the five multiplies, the
`sqrt` and the divide run in double, and only the store into a single-float parameter or moment
buffer narrows. `mk`/`vk` feed the parameter update at full double width even at `#f`, because
the defun's own locals do. Declines: a scalar parameter or gradient, a general boxed array, a
mixed-width quadruple, unequal element counts, a malformed rule vector. **The kernel mutates in
place, so every check is up front**: there is no mid-loop decline, and a declined call must
have written nothing.

**`linalg::%la-rng-fill (out st mode lo span)`** is the one fill loop `rand`/`randn`/`uniform`
share. The generator's state lives in three SPECIALS, which a kernel on this seam cannot read
or write; passing the state in as an ARRAY and answering the state it ends on as one makes the
fill a pure function of its arguments. `%la-rng-state` reads the specials into a vector,
`%la-rng-restore` writes one back, and the three callers bracket the fill with them. The
generator's rule does not move: the DEFUN still calls `%la-rng-next` in a loop, so there is
exactly one copy, and the scalar draws (`%la-rng-int`, hence `choice`/`permutation`, and
`seed`'s ten discarded draws) keep using it. `mode` picks the element rule — 0 one uniform
draw, 1 the sum of twelve minus 6 (Irwin-Hall), 2 `lo + span * draw` — each spelled exactly
where its caller spelled it.

**The closed form was not needed.** `s <- a*s mod m` has `s_k = a^k * s mod m`, so a kernel
COULD fill element k in any order; none of the three does. Each keeps the three states in
integer locals and walks in order, which is bit-identical by construction and is where the win
already is: what the defun paid was a boxed double per draw (twelve per `randn` element) and a
boxed integer per state update, not the sequencing. Keep the closed form in mind only for a
device kernel. The kernel declines a state vector that is not three packed doubles that are
exact non-negative integers below `2^23` — the range `linalg:seed` produces and `%la-rng-next`
keeps, and the range in which `a * s` cannot overflow an `int` and Java `%` / wasm `i32.rem_s`
agree with Lisp `mod`. Also declines a boxed destination, a mode outside 0..2, a non-numeric
`lo`/`span`. **`linalg:seed`'s promise makes byte identity non-negotiable**: one seed
reproduces one sequence on every backend, and the examples' expected output is pinned to it.

2.2x per training step on a `--gpu --simd` build, 1.05x on a CPU-only `--simd` one: once
`--gpu` has taken `linalg:`, these two loops ARE the step, while under `--simd` alone the
matrix product still is. **Do not size the JVM gain from a micro-benchmark**: called with ONE
set of arrays, escape analysis scalarizes the boxing away; in the training program the same
loop runs over dozens of differently shaped arrays and does not, which is why the profile
found 22% where a micro-benchmark suggests 2%.

`torch::%o-sgd-step` was deliberately NOT moved: same shape of loop, but on no profile — a
second fused member is only worth writing when something measures it.

### The selects and copies: comparison masks, `where`, the strided gather, `take-rows` and its adjoint, `clip-grad-norm`'s halves

Eleven members in one round, for the same reason: a JFR profile taken after the previous round
said 40% of what was left of a training step was boxed `row-major-aref` walks —
`linalg:where` through `torch:masked-fill` (and its `%la-broadcast-to` -> `%la-gather-strided`
materializations, 22% on their own), `linalg:greater` through the dropout mask (6%, the `emap`
branch of `%la-bcast`), `linalg:slice` through `torch:cat`'s adjoint (3%),
`torch:index-select`'s inline scatter-add (5%), `torch:clip-grad-norm`'s two loops (4%, 17%
once everything else had moved) and `take-rows` (2%). None is arithmetic: every one is a
select, an IEEE compare, a copy or a widened add, so every one is **bit-identical to its defun
at both widths by construction** — the round needed no precision decision.

- **The five comparison masks** ride the element-wise dispatch: the same three `%la-bcast`
  shapes (equal dims, a scalar on either side, a broadcast pair through the BCAST walk with a
  new op code, `BOP_GT`..`BOP_EQ` = 6..10 in `LinalgSimdKernels`, the JVM template and the wasm
  builder alike), a 1.0/0.0 result at the first ARRAY operand's width, and the comparisons are
  the defun's own IEEE `>` `>=` `<` `<=` `=` on the widened elements — so `-0.0` equals `0.0`
  and NaN compares false everywhere. NOT symmetric, so the scalar-on-the-left shape runs the
  reversed loop like the selects do. Two numbers decline (the defun answers an integer).
- **`where` (arity 3)** is a three-operand broadcast walk: each operand an array of either
  width or a number, the output shape folded pairwise over the array operands, stride 0 on a
  stretched axis and no stride at all for a scalar, `(= mask 0)` as an IEEE `== 0.0` test, and
  the result at `x`'s width when `x` is an array, else `y`'s, else double — the defun's rule.
  No array at all declines, an incompatible broadcast declines, mixed widths are taken (each
  element is read widened anyway). A kernel, not a materialization: the defun builds three
  broadcast copies first and then selects, which is why it was a fifth of a step.
- **`%la-gather-strided (a od rs base single)`** — the FIFTH PARAMETER CHANGED: it was the
  element-type symbol `etype`, it is now a flag (`nil` = double, non-nil = single). The defun
  turns the flag back into the `%la-make` symbol; the two callers pass
  `(eq (linalg::%la-etype a) 'single-float)`. That is what made the member interceptable on all
  three backends without a symbol comparison (a quoted symbol is a `String` on the JVM, an
  interned `$string` offset on wasm, a `LispSymbol` on the interpreter — a nil/non-nil test is
  the one thing all three answer the same way). The kernel parses `od` (a shape designator) and
  `rs` (a proper list of i31/integers, innermost-first strides, NEGATIVE for a reversed slice),
  reverses `rs` into outermost-first strides, and **computes the walk's lowest and highest flat
  index up front** (base plus each axis's full travel, in `long` on JVM/interpreter and f64 on
  wasm), declining when either leaves `a` — so a declined call has read nothing and the defun
  signals its own subscript error per element. An empty output (a 0 extent) walks nothing and
  is always in bounds.
- **`take-rows (a idx)` and `%la-scatter-rows (z g idx)`** read the index vector exactly as the
  defun does (`(truncate (aref idx i))`) and decline when it is not a rank-1 packed vector or
  when any truncated index leaves `[0, rows)`; the check is `v > -1.0 && v < rows` on the
  double, so `-0.5` truncates to 0 like the defun and NaN declines. `%la-scatter-rows` is NEW
  in `linalg.lisp`: `torch:index-select`'s backward spelled inline, moved under a `linalg:`
  name so the seam can reach it, in place over `z` with the widened add narrowed only on a
  single-float store; it requires the two arrays to be the same width and `g`'s count to be
  `m * slab`. `take-rows` copies slabs (`System.arraycopy` on the JVM, a `_v_get`/`_v_set` walk
  on wasm).
- **`%la-sum-squares (g acc)` and `%la-scale (g s)`** are `torch:clip-grad-norm`'s two element
  loops: `(+ total (* v v))` as a LEFT fold in double from the caller's accumulator (byte
  identity, NOT a lane reduction — a `linalg:sum` of squares would follow the reduction
  contract and move the clip scale), and `(* g s)` in place. A ratio accumulator or scale
  declines to the defun's exact rational arithmetic (on wasm `isNumber` excludes the ratio
  struct for the same reason; `unboxF64` would have converted it). `torch:clip-grad-norm` keeps
  its scalar branches and its in-place contract.

Touch points: `LinalgSimd` (`define`s) + `LinalgSimdKernels` (`compare*`, `where`,
`gatherStrided`, `takeRows`, `scatterRows`, `sumSquares`, `scale`; `bcastStrides` went
package-private for `where`); `JvmLinalgKernelCompiler.KERNELS` (arity 3 for `where` /
`%la-scatter-rows`, 5 for `%la-gather-strided`) + `JvmSimdVectorTemplate` (`laGreater`..
`laEqual` through `laElementwise` with `OP_GT`..`OP_EQ`, `laWhere`, `laGatherStrided`,
`laTakeRows`, `laScatterRows`, `laSumSquares`, `laScale`); `WasmLinalgSimdCompiler` +
`WasmLinalgSimdRuntimeBuilder` (`COMPARE_GT`..`COMPARE_EQ` 45..49, `WHERE` 50,
`GATHER_STRIDED` 51, `TAKE_ROWS` 52, `SCATTER_ROWS` 53, `SUM_SQUARES` 54, `SCALE` 55;
`FUNC_COUNT` 45 -> 56, `userFuncBase()` shifts by 111 under `--simd`; the 3-param members on
`TYPE_CALLABLE_BASE + 2`, the 5-param one on `+ 4`). The wasm builder grew two generic helpers:
`emitOdometerN` (the carry over ANY number of stride/offset pairs — three for `where`; the old
two-pair `emitOdometer` delegates to it) and an `emitBcastShape` overload with an explicit
decline depth (the original hard-coded `br_if 2`, only right at the exit block's top level;
`where` folds the shape inside two `if`s and needs 4). `emitApplyBop` branches on
`op >= BOP_GT` first, so the BCAST kernel answers the comparison masks at a broadcast shape
with a select chain over the op.

Verification: `LinalgSimdTest.theSelectsAndCopiesAre{InterceptedUnderSimd,BitIdenticalToTheScalarOracleAtEveryShapeAndWidth}`
and `...DeclineWhatTheDefunSignalsAndSignalItUnchanged` (the dead-flag guard over all eleven
names, 40-odd value cases over every shape and width — scalars on either side, broadcast pairs,
`-0.0`, a reversed slice, an empty index vector, mixed widths, a ratio accumulator, boxed
operands — and a `torch:` program driving them through `masked-fill`, `index-select`, `cat`,
`slice` and `clip-grad-norm` on one seeded tape; it uses `torch:mul` rather than `torch:matmul`
between the tensors, because an f32 MATMUL is the one member in that chain that is NOT
byte-identical under `--simd`);
`JvmLinalgSimdAccelCompilerTest.theSelectsAndCopiesAre{ByteIdenticalToTheScalarPathAtEveryShapeAndWidth,EvaluateTheirArgumentsExactlyOnce}`;
`WasmLispCompilerIntegrationTest.wasmGcSimdLinalgSelectsAndCopiesAreByteIdenticalToTheScalarPath`.

## What is vectorized, and what is merely de-boxed

Not every member has a lane form worth writing. Interception is still worth it for all
forty-nine: it removes the per-element box allocation and the generic numeric dispatch the
compiled defun pays, and on the interpreter the whole tree-walking loop.

| member | interpreter / JVM | wasm-GC |
|---|---|---|
| `add`/`sub`/`mul`/`div`, array with array | lane loop | `gcMap2` lane loop |
| the same, array with a **double** scalar | lane loop | `gcBroadcastF64` lane loop |
| the same, array with a **single** scalar | scalar loop | `_v_get`/`_v_set` element loop |
| `sum`, `norm`, `dot` (v.v), `dot` (M.v = GEMV) | lane loop (reuses `VecSimdKernels`) | calls the `vec:` kernels |
| `dot` (v.M), `dot` (M.M) | `ikj` lane loop over the output row | `ikj` lane loop: shuffle-window b rows into a scratch row of the operand width, `_v_set` write-out |
| `outer` | lane loop over the row | whole destination groups when `m % lanes == 0`, else `_v_get`/`_v_set` |
| `amax`/`amin`/`argmax`/`argmin`/`trace` | scalar loop | `_v_get` element loop |
| `sqrt`/`abs`/`negative` | lane loop | `gcMap1` lane loop (defun-mirroring forms) |
| `maximum`/`minimum`, array with array | scalar select loop (perf-only choice; bits identical either way) | `gcMap2Select` gt/lt mask + bitselect lane loop, BOTH widths |
| the same, array with a **double** scalar | scalar select loop | `gcBroadcastSelectF64` lane select (save/restore bracket kept: a select over padding can answer s) |
| the same, array with a **single** scalar | scalar loop widened vs the full double | `_v_get`/`_v_set` element loop widened vs the full double |
| `exp`/`log`/`tanh`/`sin`/`cos`/`tan`/`asin`/`acos`/`atan`/`sinh`/`cosh`/`sign` | de-boxed scalar loop (the same `java.lang.Math` call) | `_v_get`/`_v_set` element loop emitting the defun's f64 sequence |
| `transpose` | scalar loop | lanes x lanes register-block shuffles when BOTH dims are lane-aligned, else `_v_get`/`_v_set` |
| `reshape` | `Arrays.copyOf` | whole lane-group copy |
| same-width broadcast pair (any op) | scalar odometer walk | `BCAST` odometer walk (op as an i31 selector) |
| `transpose` with axes | scalar odometer copy | `TRANSPOSE_AXES` odometer copy |
| `sum`/`amax`/`amin`/`argmax`/`argmin` with axis | scalar fold loops | `*_AXIS` fold loops |
| `%la-matmul-nd` | the `dot` M.M lane loop per batch offset | `MATMUL_ND`: `dot`'s `emitGemmRow` per batch offset |
| `erf` | de-boxed scalar loop | `ERF` element loop, series inline |
| `%la-rng-fill` | de-boxed scalar loop, states in `int` locals | `RNG_FILL` `_v_set` element loop, states in i32 locals |
| `%la-adam-step` | de-boxed scalar loop over four arrays | `ADAM_STEP` element loop |

The wasm-GC lane forms for `dot` (v.M / M.M) / `outer` / `transpose` read each `b` row through
the same `i8x16.shuffle` window as `vec:matvec`
(`WasmVecSimdRuntimeBuilder.emitRowGroup`, promoted package-private) and multiply-accumulate
whole groups into a **scratch row of the operand width** (`_v_new(p, kind)`, reused and
re-zeroed per output row), written out element-wise through `_v_set` — O(n·p) writes against
O(n·m·p) flops. One loop serves both widths: the group count is the scratch row's own and every
window group maps to the accumulator group of the same index, so the only per-width difference
is `f32x4`/`f64x2` and the splat of `a[i][k]` (demoted through `f32.demote_f64` at `#f`). The
window's overhang past a row's end reads REAL next-row elements (unlike matvec's zero-padded x)
but lands only in accumulator lanes past `p`, which the write-out never reads. An `#f` scratch
row is what makes the matrix product a reduction-contract kernel rather than a bit-identical
one, and all three `--simd` backends had to move together. wasm accumulated in f64 until then,
widening through `f64x2.promote_low_f32x4`; that instruction is no longer emitted (its
`WasmSections.skipSimd` case stays, harmless). `transpose` uses two shuffles per 2x2 f64 block
and the classic eight-shuffle butterfly per 4x4 f32 block.

`norm` is **fused**: the oracle spells it `(sqrt (sum (emap square a)))` and allocates an
intermediate array per call; every kernel computes `sqrt(dot(a, a))` instead.

`amax`/`amin` are deliberately scalar. A lane `MAX` reduce is wrong twice over: the last
group's padding lanes are **zero**, so an all-negative array would answer `0`; and a horizontal
fold loses the defun's "first strictly greater wins" tie-break. Pinned by
`amaxAndAminKeepTheOracleStrictComparisonSemantics` and the all-negative case in every backend.

## The precision contract

Element-wise results are **bit-identical** to the scalar `linalg.lisp` oracle at both widths.
Only reductions move.

- **`array (+) array` at single width computes natively in `float`, and that is exact.** `f64`
  carries 53 bits and a `float` 24, so `53 >= 2*24 + 2` and the oracle's widen-compute-narrow
  round trip yields the correctly rounded `float` for `+`, `-`, `*` and `/` alike (the
  innocuous-double-rounding bound; it is why `div` could join `add`/`sub`/`mul` in the f32 lane
  loop).
- **`array (+) scalar` at single width does NOT enjoy that bound** — the scalar is a full
  `double`. Those kernels compute in `double` and narrow once. On interpreter/JVM they are
  scalar loops (widening an f32 lane would need `FloatVector.convert(F2D)`); on wasm-GC they
  walk `_v_get`/`_v_set`, which promote and demote for free. **`(linalg:mul grad 0.1)` over an
  `#f` gradient is the common shape** (`examples/ml/nn-vec.lisp`) — splatting `(f32) 0.1` into
  f32 lanes would move its printed output. Pinned by
  `aSingleFloatArrayBroadcastAgainstAnInexactScalarStaysBitIdenticalToTheOracle`.
- **Reductions**: an `#f` reduction accumulates in single precision and promotes to f64 once,
  at the value boundary. Covers `sum`, `mean`, `norm`, `dot` (v.v) and `dot` (M.v).
- **The matrix product follows the reduction contract too.** `dot` (v.M) and `dot` (M.M)
  accumulate in the OPERAND width, folding each output cell over `k` in the oracle's ascending
  order. The defun cannot follow — rontolisp has one float type and it is f64 (`LispNames`:
  "Every float shares the one double") — so there is no version where oracle and kernel agree.
  Measured worst case: max ~3-4% RELATIVE error, always on a cell whose true value has
  cancelled to near zero; that is what every f32 GEMM does, PyTorch's CPU `sgemm` included.
  **Which lanes ran cannot move the answer**: the lanes go across the output row (the `j`
  axis), which carries no summation, so the lane loop, the scalar tail and the wasm f32x4 loop
  fold every cell identically and the three `--simd` backends agree bit for bit. At `#d`
  nothing changed.
- **Why not keep the f64 accumulator, which WAS bit-identical?** It forbids lanes: an f64
  accumulator can only be fed by widening each f32 lane group through
  `FloatVector.convert(F2D)`, and where that intrinsic is missing the widening is catastrophic.
  `MatmulFProbe.java` (in the GPU item's directory) measures all four candidate kernels head to
  head and is rerunnable; **it answers differently per architecture, so rerun before quoting**.
  On NEON `convert(F2D)` has no intrinsic at all (125-190x slower than the scalar loop it would
  replace) and the scalar f64-accumulator kernel left `#f` matmul **2x slower than `#d`**; on
  x86-64 the conversion IS intrinsified and the symptom does not reproduce. What holds on BOTH
  is the ranking: f32 lanes with an f32 accumulator wins, and the F2D widening never does.
  **Do not port it to the JVM, and do not spend time on `convertShape` variants.** `#f` is now
  the faster width on both scalar backends.
- **`trace`, `amax`, `amin`, `argmax`, `argmin`** are bit-identical: they read elements widened
  to `double`, exactly as the defun does.
- **`erf` is bit-identical at both widths**: `%la-erf-1`'s series runs in `double` and narrows
  only on a single-float store, which is what the `emap` defun does. It is NOT a reduction.
- **The declined-shape follow-up kernels are ALL bit-identical at both widths.** The broadcast
  and the axes transpose read widened, compute in double and narrow only on a single-float
  store; the transpose is a pure copy. The AXIS folds do NOT follow the lane-reduction
  contract: `(linalg:sum a :axis 0)` accumulates in f64 from the defun's `0` seed in the defun's
  order (an axis fold is a scalar loop, not a lane reduction — only the no-axis
  `sum`/`dot`/`matvec` lanes reduce), and the `amax`/`amin` folds mirror `(if (> x acc) x acc)`
  — the ACCUMULATOR wins ties/NaN, the opposite of the element-wise select, so
  `(linalg:amax #d((-0.0 0.0)) :axis 1)` is `#d(-0.0)` while `(linalg:maximum #d(-0.0) #d(0.0))`
  is `#d(0.0)`. A vector reduced without keepdims returns the boxed f64 accumulator itself
  (never narrowed, even for `#f`), and the axis `argmax`/`argmin` results are packed DOUBLE
  arrays at any input width — exactly as the defuns answer.
- **`ikj` is not just faster, it is bit-identical at double width.** The oracle's naive `ijk`
  reads `b[k][j]` with stride `p`, which no lane loop can follow. `ikj` makes `b[k][*]` a
  contiguous row AND visits `k` in the same increasing order into the same accumulator cell —
  the oracle's own summation order. No transpose, no scratch buffer, no tiling.

### The probe that pins it

`v = #f(4096.0 1.0 ... 1.0)`, 1024 elements. `dot(v,v) = 4096^2 + 1023 = 16778239` exactly;
`4096^2` is `2^24`, where the f32 spacing is 2, so the lane holding it swallows every `1.0`
added to it while the other three fold 256 ones each -> `2^24 + 768 = 16777984`.

| probe | scalar (all backends) | `--simd` (all backends) |
|---|---|---|
| `(round (linalg:dot v v))`, `v[0] = 4096.0` | 16778239 | **16777984** |
| `(round (linalg:sum v))`, `v[0] = 2^24` | 16778239 | **16777984** |
| `(round (* 1024 (linalg:mean v)))`, `v[0] = 2^24` | 16778239 | **16777984** |
| `(round (aref (linalg:dot (reshape v '(1 1024)) v) 0))` — GEMV | 16778240 | **16778176** |
| `(round (aref (linalg:dot v (reshape v '(1024 1))) 0))` — **v.M** | 16778240 | **16777216** |
| any of the above at `#d` width | 16778239 | 16778239 |

**The GEMV row moves with `vec:matvec`'s.** `linalg`'s matrix-by-vector case is NOT a kernel of
its own: `LinalgSimdKernels.matvecF` delegates to `VecSimdKernels.matvecF`,
`JvmSimdVectorTemplate` reaches the same `matvecRowsF`, and the wasm builders route it via the
`vec:` matvec kernel. So when that row gained four independent accumulators above 32 columns,
this probe moved to `2^24 + 960 = 16778176`. Forking the kernel to hold the old value was
rejected: it would duplicate code to preserve a number nothing promised and leave `linalg:dot`'s
M.v slower than `vec:matvec`. `linalg:dot` of two VECTORS, `linalg:sum`, `linalg:mean` and the
matrix-matrix product (`matmulRowsF`, its own kernel) are unchanged.

**Nothing else catches a regression here**: every other `#f` test input stays under `2^24`,
where an f32 accumulator is exact. Pinned three times — `eval/LinalgSimdTest`,
`codegen/jvm/JvmLinalgSimdAccelCompilerTest` (both
`singleFloatReductionsAccumulateInSinglePrecisionUnderSimd`) and
`WasmLispCompilerIntegrationTest.wasmGcSimdLinalgSingleFloatReductionsAccumulateInSinglePrecision`.
The three `--simd` backends print the same seven lines, so they pin each other too.

## Float comparison semantics: `>` and the signed-zero surface

`amax`/`amin`/`argmax`/`argmin` compare with `>`. Historically rontolisp's `>` on two floats was
three different operations (interpreter `Double.compare`, a total order; JVM `DCMPL` for every
operator; wasm literal `f64.gt` but a signum `_rat_cmp` through variables), so each kernel
mirrored ITS OWN backend. The scalar comparisons were fixed on all three backends (interpreter
`compareNumeric` gained an UNORDERED state; the JVM literal path picks DCMPG/DCMPL per operator
and the runtime path uses the `_cmpb` bitmask; wasm's variable path funnels through
`_rat_cmp_bits`), and `LinalgSimdKernels` switched from `Double.compare` to plain Java `>`/`<`
in the same change — the required lockstep. All three kernels now compare IEEE and match their
own scalar defun (pinned by `LinalgSimdTest`'s `-0.0` oracle-match cases):
`(linalg:amax #d(-0.0 0.0))` is `-0.0` everywhere.

The whole signed-zero surface now reads identically on interpreter, JVM, wasm-GC, component AND
`--no-gc`, pinned by ci-spec cases `signed-zero-across-every-float-operator` and
`min-max-nan-is-unordered-so-the-right-operand-wins`. Settled answers (`nz` = `-0.0`,
`pz` = `0.0`), literal path and variable path alike:

| form | answer | why |
| --- | --- | --- |
| `(+ nz nz)` / `(+ nz pz)` | `-0.0` / `0.0` | IEEE 754 |
| `(min nz pz)` / `(min pz nz)` | `-0.0` / `0.0` | tie keeps the LEFT operand |
| `(max nz pz)` / `(max pz nz)` | `-0.0` / `0.0` | tie keeps the LEFT operand |
| `(min nan x)` / `(min x nan)` | `x` / `NaN` | unordered, so the RIGHT operand wins |
| `(signum nz)` / `(signum nan)` | `-0.0` / `NaN` | `Math.signum` |
| `(sin nz)` / `(tan nz)` / `(tanh nz)` | `-0.0` | odd functions |
| `(mod nz 2.0)` / `(rem nz 2.0)` | `-0.0` | the zero `truncate`/`floor` leaves |
| `(mod nz -2.0)` / `(rem -4.0 2.0)` | `0.0` | `-0.0 - (-0.0)` and `x - x` are `+0.0` |
| `(eql nz pz)` / `(equal nz pz)` | `NIL` | float `eql` is a BIT compare |

**`min`/`max` are one rule on both edge axes**: `min(a,b) = (a <= b) ? a : b` and
`max(a,b) = (a >= b) ? a : b` with IEEE comparisons — an equal-value tie keeps the accumulator
(leftmost argument wins) and an unordered pair fails the test (a NaN operand always yields `b`).
CLHS leaves the tie implementation-dependent and says nothing about NaN, so the oracle is
upstream: **verified against SBCL bit-for-bit over all 98 ordered pairs from
`{-0.0, 0.0, 1.0, -1.0, NaN, +inf, -inf}`**. Deliberately NOT `Math.min`/`f64.min`, which
resolve the tie by SIGN and propagate NaN from either side. Before the fix the tie was a
THREE-way split through variables (interpreter by sign, JVM first argument, wasm second) AND
disagreed with each backend's own double-LITERAL fast path. The literal fast paths survive
(`JvmMinCompiler` calls the new `_fmin`/`_fmax` `(DD)D` helpers, `WasmMinMaxCompiler` emits a
native `f64.le`/`f64.ge`); they just compute the same select.

- **`eql`/`equal` on `-0.0` vs `0.0`** is `NIL` everywhere (CLHS makes the two `=` but not
  `eql`; SBCL agrees). wasm compared with `f64.eq` and answered `T`; it now compares bits, OR-ed
  with a both-NaN arm so it stays identical to `Double.equals` (which folds every NaN onto one
  pattern, so a NaN and that same NaN negated are still `eql`).
- **`signum`/`sin`/`tan`/`tanh`** flattened on wasm because each computes the answer by a route
  that erases the sign — `(x>0)-(x<0)` is `+0.0` for `+0.0`, `-0.0` and NaN alike; the
  Cody-Waite reduction's `-0.0 - (-0.0)` cancels to `+0.0`; `tanh`'s `(e-1)/(e+1)` is `0.0/2.0`
  once `exp(-0.0)` has rounded to `1.0`. Each now guards the zero (and, for `signum`, the NaN)
  case and answers the argument itself. The `--simd` kernels
  (`WasmVecSimdRuntimeBuilder.emitSignumF64` / `emitSinCosF64` / `emitTanhF64`, shared by
  `vec:`, `linalg:` and `--no-gc`) carry the SAME guards, pinned byte-identical to the scalar
  defun path by `wasmGcSimdUnaryUfuncsAreByteIdenticalToTheScalarPath` and its `linalg` twin,
  which fail if only one side is changed.

**Float CONTAGION in `min`/`max`**: `(min 1 2.0)` was `1.0` on the interpreter
(`Environment.registerArithmetic` coerced whenever any argument was a float), the integer `1` on
both compilers' general boxed path, and `1.0` on the JVM's double-literal fast path — so the JVM
disagreed WITH ITSELF depending on whether an operand was written as a literal. **SBCL answers
`1`** (`(min 0 -0.0)` is the integer `0`, `(min -0.0 0)` is `-0.0`, `(min 1 2.0)` /
`(min 2.0 1)` are both `1`), and CLHS leaves contagion implementation-dependent. Fix:
`registerArithmetic`'s `min`/`max` no longer coerce, and the JVM's double-literal fast path was
NARROWED, not dropped — `JvmMinCompiler`/`JvmMaxCompiler` gate it on
`JvmLispCompiler.isDefinitelyDouble` (each operand independently PROVEN double: a literal, a
declared/raw double local, or a nested `+`/`-`/`*`/`mod`/`rem` tree with a provably-double
operand) instead of `hasDoubleLiteral` (any operand merely CONTAINING a double literal
anywhere). When both operands pass, the winner is a double whichever wins, so the raw-double
compare-and-rebox is exact. `.kb/jvm-double-arithmetic.md` has the full predicate. All four
backends now answer `1`. Narrowing the GATE while leaving the emission untouched changes no
measured number; real scalar `min`/`max` sites with mixed literal/non-literal double-ish
operands do exist (`examples/macos/*.lisp` per-frame clamps), which is why it was narrowed
rather than dropped.

**`--no-gc` is a genuine, permanent exception**: its type inference gives every
`+ - * mod rem abs min max` call site ONE static WASM type — `FLOAT` iff any operand is `FLOAT`
(`NoGcWasmCompiler.typeOf`) — because it has no boxed/dynamic value representation to hand back
"the operand as it stood" from a site the verifier has already typed `f64`. `(min 1 2.0)` is
`1.0` there; changing it would mean giving `--no-gc` a dynamic numeric tower, which is the
reason `linalg:` cannot compile there at all.

Everything else in the sweep agreed on all four from the start: `abs`, unary minus, binary
`+ - * /` sign propagation, `1/x` on either zero, `sqrt`, `float`,
`floor`/`ceiling`/`truncate`/`round`, `asin`, `atan`, `exp`, `expt`,
`< > = zerop minusp plusp`, `equalp`, `princ-to-string`, `format ~a`/`~s`, `coerce` to either
width, and a `#f` element stored and read back.

## `mod` / `rem` and the floor family

**A zero remainder is the one CLHS's own definition produces, NOT IEEE `fmod`'s.** CLHS defines
`rem` as the remainder of `truncate` and `mod` as the remainder of `floor`, with a quotient
that "always represents a mathematical integer" and `quotient*divisor + remainder = number`. So
the remainder is `a - b*q` for an exact INTEGER `q`, and an integer zero is `+0`, not `-0.0`.
What decided it was internal, not a preference: rontolisp's `truncate`/`floor` already answered
`0.0` for the second value of `(truncate -4.0 2.0)` while `rem` answered `-0.0`. SBCL agrees row
for row over the 46-form sweep; ci-spec case
`mod-rem-zero-is-the-truncate-and-floor-remainder`.

Stated as a correction to `fmod`: **a zero remainder is `-0.0` only when the dividend is `-0.0`
and the divisor is positive, and `+0.0` otherwise.** It falls out of `a - b*q` with integer `q`
— `q` is `+0` for a zero dividend, so `b*q` carries the DIVISOR's sign; for a nonzero dividend
`b*q` is the dividend itself, whose `x - x` is IEEE `+0.0`. The interpreter and JVM keep Java's
exact `%`/`DREM` and re-derive only the zero (`_frem`, which `_fmod` calls first — its
divisor-sign correction never fires on a zero, so `mod` and `rem` share one zero); both wasm
compilers reach the same zero from the exact reduction below.

**The magnitude is NOT the f64 evaluation of that formula, and SBCL is not the oracle for it.**
`a - b*q` for exact integer `q` has ONE right value, always representable as a double
(`|a - b*q| < |b|`, and the difference of two representable multiples at one exponent scale is
exact — which is why IEEE defines `fmod` as exact). Evaluating the formula in f64 rounds twice.
`(rem 1d18 7.0)` is `1.0` here (`10^6 = 1 mod 7`) and `0.0` on SBCL, whose `7.0*q` rounds back
to `1d18`. So CLHS's words decide the SIGN and the mathematics decides the magnitude.

Both wasm backends reach that exactness through `codegen/wasm/WasmFmodRuntimeBuilder`, emitted
into the wasm-GC `_rat_rem`/`_rat_mod` float arm and INLINED at the site by
`NoGcWasmCompiler.compileModRem` (that backend emits helper functions only behind linear memory)
— one builder, so the two cannot drift. It is the textbook exact `fmod`: scale `|b|` up by
powers of two while it fits under `|a|`, then walk back down subtracting where it fits, every
step exact (doubling/halving a power-of-two multiple is exact; each subtraction runs under
`d <= x < 2d`, Sterbenz's lemma), terminating in `exponent(a) - exponent(b) + 1` steps (~945 for
`(rem 1d-300 4.9d-324)`, the worst case). An INFINITE divisor takes no loop: the truncating
quotient of a finite dividend is integer zero, so the remainder is the dividend
(IEEE `fmod(x, inf)`), and `mod`'s divisor-sign correction turns the opposite-sign case into
`a + b` — `(rem 3.0 inf)` = `3.0`, `(mod -3.0 inf)` = `Infinity`. SBCL signals on every infinite
operand, so that row has no upstream oracle. A zero divisor is `NaN` on all five (SBCL signals
`division-by-zero`), the same non-trapping float policy `(/ 1.0 0.0)` follows.

**Trap: `mod`'s sign correction must not multiply the operands.** Both the JVM's `_fmod` and the
first draft of the wasm arm tested "opposite signs" as `r * b < 0`; that product UNDERFLOWS to
zero when both are tiny, so the correction silently did not fire and `mod` answered `rem`'s
negative remainder — `(mod -1.2345678d-296 1d-300)` was `-6.78d-301` on the JVM against the
interpreter's `3.22d-301`. All backends now compare the two signs directly.

Pinned by ci-spec `mod-rem-are-the-exact-float-remainder-at-any-magnitude` (which carries the
`--no-gc`-free four) plus, per backend, a 240-pair DIFFERENTIAL against the interpreter over a
value set crossing 2^53, 2^63, the subnormals and both signed zeros —
`theFloatRemainderMatchesTheInterpreterOverAMagnitudeSweep` in `WasmLispCompilerIntegrationTest`
and `JvmLispCompilerTest`, with `--no-gc` and the component on the fixed table beside them.

**The QUOTIENT is exact at every magnitude too.** It used to be the double `a/b` narrowed into a
`long`, which rounds twice and then CLAMPS: `(truncate 1d300 7.0)` answered `Long.MAX_VALUE`
with `1.0e300` as its remainder. Now all four backends divide the two operands AS THE EXACT
RATIONALS THEY ARE — a finite double is `mantissa * 2^exponent` exactly, an exact integer is
itself over one — and round that rational, giving the mathematical integer CLHS asks for, a
bignum when it has to be. `(floor 1d300)` is the 301-digit exact value of that double.

**SBCL is not the oracle for the two-argument rows** (it rounds `a/b` in f64 and converts THAT
exactly, so its `(truncate 1d300 7.0)` quotient ends `...39008` where the exact one ends
`...05737`). The one-argument rows DO match SBCL (no division happens). **The change is not
confined to huge magnitudes**: the double `0.1` is a shade ABOVE a tenth, so `1.0` divided by it
is `9.9999999999999994...` and its floor is `9`, not `10`. `(floor 1.0 0.1)` is now `9` with a
remainder of `0.09999999999999995` — what `mod` already answered there.

The second value is no longer computed as `x - q*y` (with `q` an exact bignum and `y` a float
that product rounds and the difference loses the answer). `LispMacroExpander.lowerMvProducer` —
the ONE lowering all four backends share — reads each operator's remainder off `rem` and `mod`:
`truncate`'s is `(rem a b)`, `floor`'s is `(mod a b)`, `ceiling`'s is `(- (mod (- a) b))`
(negating the DIVIDEND, not subtracting a divisor from `mod`, which would round twice and lose a
tiny dividend: `(ceiling 1d-300 -7.0)` is `1d-300` while `mod` there is `-7.0`), with a zero
taking `rem`'s zero so the sign rule is not flipped by the negation, and `round`'s is whichever
of the two its quotient landed on. A divisor of `1` covers the one-argument forms, so there is
one formula.

Per backend: the interpreter's `eval/ExactRounding` (reached from `LispEvaluator.evalCons`,
which recognizes both `(op a b)` and the `(op (/ a b))` its lowerings leave behind), the JVM's
hand-assembled `_fdiv`/`_frat` (`JvmNumericRuntimeBuilder`; `_frat` reads a double's exact value
through `new BigDecimal(double)` and the pair divides through the existing `_div` + rational
rounders), and wasm-GC's `_f64_fdiv` (`WasmFloatFdivRuntimeBuilder`, building the two rationals
from the IEEE bits and handing them to the limb-tier `_big_fdiv`). All three DECLINE — keeping
the old f64 route — for a ratio operand, a non-finite dividend and a zero divisor, so
`(truncate 1.0 0.0)` is unmoved. Pinned by ci-spec
`the-floor-family-quotient-is-exact-at-any-magnitude`,
`LispEvaluatorTest.theFloorFamilySecondValueIsTheRemainderCLHSDefines` (an exact-rational oracle
sharing no code with the implementation, over all four operators) and a per-backend differential
`theFloorFamilyMatchesTheInterpreterOverAMagnitudeSweep`.

**An INFINITE divisor is settled by sign, not declined.** It used to keep the f64 answer
(`(floor -3.0 inf)` was quotient `0`, from `Math.floor(-0.0)`) while `mod` already answered the
limit value, so the two halves of one operator disagreed. With a FINITE NONZERO dividend, `a/b`
is an infinitesimal whose magnitude is always under 1/2, so `truncate` and `round` are always
`0`, and `floor`/`ceiling` round that infinitesimal down or up — `0` and `1` when dividend and
divisor agree in sign, `-1` and `0` when they do not. `(floor -3.0 inf)` is `-1` with remainder
`Infinity`. An EXACT-ZERO dividend (`0.0`, exact integer `0`, or a value declining for another
reason) still takes the old route: `0/infinity` is genuinely zero, not an infinitesimal. Per
backend: `eval/ExactRounding.infiniteDivisorQuotient`,
`JvmNumericRuntimeBuilder.emitInfiniteDivisorQuotient` (a new arm inside `_fdiv`, gated on
`Double.isInfinite` before the operands reach `_frat` and decline), and
`WasmFloatFdivRuntimeBuilder.emitInfiniteDivisorQuotient` (the same gate inside `_f64_fdiv`,
reusing `_big_cmp` to read an exact-integer dividend's sign). Pinned by extra rows in the ci-spec
case, `LispEvaluatorTest.theFloorFamilyQuotientWithAnInfiniteDivisorComposesWithItsOwnRemainder`
and a per-backend `theFloorFamilyQuotientWithAnInfiniteDivisorMatchesTheInterpreter`.

**`--no-gc` cannot follow and is the one documented divergence** (two rows). It is i64-native by
design with no bignum tier (`.kb/wasm-bignum.md`), so `(floor 1d300)` TRAPS
(`i64.trunc_s_f64`, not the saturating form) and `(truncate 1d18 7.0)` keeps the rounded-double
quotient `142857142857142864`; an exact quotient needs the shifted mantissa product, which
overflows i64 for any exponent spread past ~10. `(floor -3.0 inf)` stays `0` there because it
lowers `(floor a b)` to `(floor (/ a b))`. The remainder side is unaffected: the exact `fmod` is
inlined there and still exact.

`ffloor`/`fceiling`/`ftruncate`/`fround` do not exist in rontolisp at all (a separate item).

## Per-backend mechanics

### Interpreter

`LinalgSimd.install(globalEnv, evaluator)` runs right after `linalg.lisp` is evaluated in
`LispEvaluator.resolveFunction`'s lazy-load hook, guarded by `this.simd`. Each override
**captures the defun it replaces** and applies it on decline through the package-private
`LispEvaluator.applyGlobal` seam. Unwrap is zero-copy:
`LispDoubleFloatArray(double[] data, int[] dims)`.

`LinalgSimd.available()` / `install(...)` are the ONLY entry points into `LinalgSimdKernels`,
which is what makes `src/web/java/.../Target_LinalgSimd.java` sufficient to cut the incubator
Vector API out of the browser Web Image. `LinalgSimdKernels` delegates the shared kernels to
`VecSimdKernels` rather than copying them, so `LinalgSimd` never touches `VecSimdKernels`
directly. **A new public method on `LinalgSimd` that touches the kernels would break it, and
only the Pages workflow's Web Image build would notice** (`web-playground-native-image-gotcha`).
`./mvnw -Pweb compile` is the local check. `RontoLispCli.interpret` probes `VecSimd.available()`
once for both packages (same incubator module).

### JVM

**One bridge class** (`JvmSimdVectorTemplate`), so one `_simdInit` and one
`resource-config.json` entry — a second template class would need its own entry and the failure
would be at RUN time. `JvmSimdRuntimeBuilder` registers the `la*` method refs under
**package-prefixed keys** (`"linalg:add"`; internal members under their double-colon spelling
`"linalg::%la-im2col"`), because `vec:add` and `linalg:add` share a member name. Descriptors are
composed per member arity (1/2/3/5/6 `Object` params).

**`jdk.incubator.vector` is an optional module**: `_simdInit`'s `Lookup.defineClass` resolves
the template's verifier-visible types AT THAT CALL, so a JVM without
`--add-modules jdk.incubator.vector` fails to LINK the bridge — before any bridge method runs,
unlike `--blas`/`--gpu`, whose probe is a method call inside an already-linked bridge.
`_simdInit` catches the `LinkageError`, prints the same one-line warning `RontoLispCli.enableSimd`
prints (once — the `_simdInited` guard field also gates the catch), and leaves `_simdAvailable`
false; `_simdReady()` exposes it as an `ops` entry (`"available"`). Every accelerated call site
— `JvmSimdCompiler`'s `vec:` sites, `JvmSimdCompiler.compileGpuMatvec`'s `--gpu`-declined rung,
and the `--simd` rung — checks `_simdReady()` BEFORE emitting a call that would resolve a method
reference into the bridge, instead of letting the class-define failure surface as a raw
`NoClassDefFoundError`. `JvmSimdModuleFallbackTest` pins this by running a compiled class in a
fresh child JVM that never sees `--add-modules`.

The compiled packed array carries an **in-array header** `[rank, dim..., data...]`,
`off = 1 + rank`. So an element-wise linalg kernel is the `vec:` one at a different offset, and
the fresh result must copy the whole header (`laNewLike`) rather than write `[1, n]`.

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

The gate is `JvmLispCompiler.programUsesAnyAcceleratedSimdOp`, scanning both packages. It scans
AFTER `LinalgLibrary.process` has spliced the defuns, so ANY linalg program embeds the bridge
(the spliced `linalg.lisp` itself contains the accelerated call sites). `(print (+ 1 2))` does
not.

### wasm-GC

Fifty-six standalone functions at `WasmLispCompiler.linalgFuncBase()` = `FUNC_VEC_BASE + 55`
(the `vec:` block is 55), emitted only under `--simd`; `userFuncBase()` shifts by 111. Index
layout, newest first: `COMPARE_GT`..`SCALE` 45..55 (the selects and copies); `RNG_FILL` 43 and
`ADAM_STEP` 44 (both on the always-present five-eq-param `TYPE_CALLABLE_BASE + 4` type `IM2COL`
already used); `ERF` 42 (the one-eq-param `TYPE_CALLABLE_BASE`); `MATMUL_ND` 41 (an ordinary
two-eq-param type); the seven declined-shape helpers `BCAST`..`ARGMIN_AXIS` before those —
`BCAST` takes its op as an i31 (the 3-eq-param `TYPE_CALLABLE_BASE + 2`) and is called from the
six element-wise kernels' unequal-dims branch, the others from the extended call sites. **No new
type entries were needed anywhere.** The odometer/fold scratch (dims copies, strides, counters,
permutations) lives in fresh `$hash_buckets` i31 arrays — kernels cannot hold extra typed local
groups beyond the fixed `withLocals` order.
`WasmLispCompilerTest.simdAppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks` pins
the delta — the only structural guard that a build WITHOUT `--simd` stays byte-identical to one
that never knew the flag. **Update it, never weaken it.**

They must be standalone because `WasmLispCompiler` declares every extra local of a compiled defun
as one `(ref null eq)` group, so a defun body cannot hold a v128 / f64 / i32 local. Local
declarations go through the shared `WasmVecSimdRuntimeBuilder.withLocals` in its fixed order —
i32, f64, f32, v128, `(ref null eq)`, `(ref null $v128arr)` — which all the index arithmetic
assumes. Several generic emit helpers there (`farrayField`, `loadHeader`, `unboxF64`,
`makeFarray`, `boxFloat`, …) were promoted from private to package-private for reuse;
`WasmVecLoops` gained `gcBroadcastF64` next to `gcScale`.

Kernel structure: a `block` per shape, `br` to the outermost block to decline (the `res` local
defaults to null). `ref.test (ref $farray)` on nil is false, so a nil argument declines for free.
Three things the `vec:` kernels did not have to do:

- **Compare dims, not just counts.** `(2 3)` and `(3 2)` have the same element count but the
  defun errors on them. `emitDimsEqual` walks the two `$hash_buckets` arrays.
- **Copy dims into the result** (`copyDims`) rather than build a rank-1 header. Copied, not
  shared, matching `%la-like`'s fresh `make-array`.
- **Restore the last group's padding after a broadcast.** `gcMap2`'s bracket already handles it
  for `add`/`sub`/`mul`, and it is not optional for the rest: over the zero padding `0 - s = -s`,
  `s / 0 = inf`, and `0 / 0 = NaN` (that last is why `div` between two arrays needs it too). A
  later `sum` over the result would fold the garbage in. `gcSaveLastGroup` /
  `gcRestoreLastGroupTail` do it once per call.

`f32x4.div` (`0xFD 0xE7`) was added to `am.ik.wasm.Instruction`, to `WasmVecLoops.f32x4Of` and
to `WasmSections.skipSimd` (which throws on an unknown `0xFD` sub-opcode, by design).

`reshape` parses its shape designator in wasm: an i31, or a proper cons list of non-negative
i31s. Anything else declines. `flatten` rides on it.

## Verification

- `eval/LinalgSimdTest` (48) — interception guard (`#'linalg:add` is `#<function linalg:add>`
  under `--simd`, `#<lambda>` without; `emap`/`inv`/`det`/`solve`/`array-equal`/`mean`/`matmul`/
  `flatten` stay `#<lambda>`), byte-identity vs the oracle at both widths and both ranks, scalar
  broadcast on both sides, the declined inputs, the f32 probe, and the declined-shape follow-up
  (broadcast pairs, transpose axes, axis folds incl. the strict tie/seed semantics and the
  declined axis inputs). `erfMatchesTheScalarOracleOverTheWholeRangeAtBothWidths` covers the
  `|x| >= 6` cutoff on both sides, `0.0`/`-0.0`, negatives, the `|x| ~ 3` region and the exact
  `torch:gelu` riding on it (the JVM suite has the twin minus the gelu line: only the
  interpreter's harness has `torch.lisp` loaded).
  `theAdamStepAndTheGeneratorFillAreInterceptedUnderSimd` is the dead-flag guard; its value
  cases run four Adam steps over four aligned arrays at both widths and all three decay modes
  (plus the optimizers themselves, which only this harness can reach), and every
  `rand`/`randn`/`uniform`/`choice`/`permutation` draw from one seed interleaved with a bare
  `%la-rng-next`. Note the long-fill case compares the ARRAY, not a `linalg:sum` of it: `sum` is
  itself a lane reduction under `--simd`.
- `codegen/jvm/JvmLinalgSimdAccelCompilerTest` (34) — the bridge-embedded dead-flag guard, the
  same byte-identity set, the evaluate-once guards (base AND extended call sites), the library
  errors still signalling, the axis/broadcast/transpose-axes shapes.
- `codegen/wasm/WasmLispCompilerIntegrationTest` (Docker + wasmtime), eleven cases:
  `wasmGcSimdLinalg{ElementWiseAndShapeKernels,ReductionsAndProducts}AreByteIdenticalToTheScalarPath`,
  `...LaneProductsMatchTheScalarPathAtEveryRowLaneOffset` (the GEMM / outer / transpose lane
  forms: every shuffle-offset variant via a 7-column `#f` matrix, the odd-`p` sentinel-group
  write, aligned vs unaligned outer/transpose, a next-row inf inside the window overhang),
  `...DeclinedInputsRunTheScalarDefun`, `...SingleFloatReductionsAccumulateInSinglePrecision`,
  `...ComposesWithOptimize`, `...AxisFormsRunTheAxisKernelsAndMatchTheScalarPath`,
  `...BroadcastAndTransposeAxesMatchTheScalarPath`, `...MatmulNdIsByteIdenticalToTheScalarPath`
  (every batch shape the odometer walks plus the three declines),
  `...ErfIsByteIdenticalToTheScalarPath` (both sides of the `|x| >= 6` cutoff, `-0.0`, the
  `|x| ~ 3` region, both widths, rank 2, the boxed declines — that harness splices only
  `vec.lisp` / `linalg.lisp`, so the `torch:gelu` leg is covered by hand and by the other two
  suites), `...OptimizerAndGeneratorAreByteIdenticalToTheScalarPath` (the Adam step at both
  widths and all three modes plus its five declines, and every generator rule plus its four),
  and `...SelectsAndCopiesAreByteIdenticalToTheScalarPath`.
- `WasmLispCompilerTest.simdAppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks`.
- `ci-spec.yaml` never passes `--simd`, so the cross-backend E2E is unaffected. The component leg
  (`--component --simd`) and `--optimize` were verified by hand and by the integration test.
- `examples/ml/{tiny-llm,linear-regression,heat3d,deep-digits}.lisp` print byte-identical output
  with and without `--simd` on all three backends (`nn-vec.lisp` has a random init, so
  `examples.yaml` checks only its headings).
- `examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` prints byte-identical output with
  and without `--simd` on ALL FOUR backends, the `--component` leg included; and
  `examples/ml/tiny-llm.lisp` on all three (its one elapsed-time line aside — interpreter
  7061 -> 96 ms with the flag). At the NOTEBOOK's shapes (40 steps, so the optimizer and the
  dropout masks really run) it is byte-identical before and after on both a `--simd` and a
  `--gpu --simd` JVM class — the acceptance that matters for two members whose kernels write
  their operands IN PLACE.

## Not done

- The lane-unaligned `outer` (`m % lanes != 0`) and `transpose` (either dim unaligned) shapes
  keep the `_v_get`/`_v_set` element loop, as do `amax`/`amin`/`argmax`/`argmin`/`trace` and the
  single-float scalar broadcast. All still several times faster than the defun; a blended-edge
  lane form was judged not worth the shuffle bookkeeping.
- A linalg program dominated by `emap` / `inv` still pays `_v_get`/`_v_set` on wasm-GC and stays
  slower under `--simd` than without it. That penalty is **intrinsic** to wasm-GC `--simd`: a
  `v128` can only be read out of an `(array (mut v128))`, never out of an `(array (mut f32))`,
  so no representation is fast for both lane loops and scalar element access. `--no-gc --simd`
  is the escape hatch, and it cannot run `linalg:` at all. An uncosted follow-up: keep
  `(array (mut f64))` under `--simd` and gather lanes with 2 (f64) or 4 (f32) `array.get` +
  `replace_lane` per group — that would delete the representation switch, and with it every
  un-intercepted regression, at maybe 2-4x the kernel cost. It contradicts the original
  representation choice; measure before believing either.
- A possible `emap` special case when `f` is a *known builtin* (`#'abs`, `#'sqrt`, `#'exp`).
  `linalg:emap #'silu` in `examples/ml/tiny-llm.lisp` is a user lambda and would not benefit.
