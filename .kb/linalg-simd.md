# `linalg:` kernel interception (`--simd`)

Read [[vec]] first: this is "do what `vec:` does, for `linalg:`", reusing the `vec:` lane loops
rather than copying them. [[linalg]] has the semantics of the library accelerated. Keep the
intercepted set, the declined-input fallback and the precision contract in sync with
`doc/{en,ja}/guides/simd-acceleration.md`.

| backend | interceptor | kernels | without `jdk.incubator.vector` |
|---|---|---|---|
| interpreter | `eval/LinalgSimd` (re-`defineFunction`) | `eval/LinalgSimdKernels` | `RontoLispCli.enableSimd` warns once and leaves `evaluator.setSimd` off |
| JVM | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmSimdVectorTemplate.la*` (one embedded bridge) | `_simdInit` forces the bridge to link, catches the `LinkageError`, warns once; every call site checks `_simdReady()` BEFORE resolving a reference into the bridge |
| wasm-GC | `codegen/wasm/WasmLinalgSimdCompiler` (call site) | `WasmLinalgSimdRuntimeBuilder` | n/a |

`--no-gc` is out of scope: `linalg:` cannot compile there at all.

## The flags stacked on this seam
- **`--blas`** ([[linalg-blas]]) and **`--gpu`** ([[gpu]]) are further rungs over the same seam,
  interpreter + JVM only. `--gpu` also takes `%la-matmul-nd`, the element-wise members whose scalar
  cost is a libm call, and the members whose CPU kernel BELOW is a scalar odometer walk (broadcast
  element-wise, `:axis` folds, `transpose` with axes). **That last set is chosen against the CPU
  column in THIS file.** Hence `JvmLinalgKernelCompiler` emits a CHAIN of up to three attempts over
  one set of temps, ending at the scalar defun, at the extended call sites too. With `--blas`/`--gpu`
  off a `--simd` build is unchanged.
- **`--parallel`** ([[simd-parallel]]) is a modifier, not a rung: the matrix products run a row
  range per thread and stay bit-identical.
- **`--gpu` is the one flag whose element-wise results are NOT bit-identical to the defun** -- its
  TRANSCENDENTAL tier only; the strided tier is bit-identical.

## Why: `--simd` used to make `linalg:` SLOWER on wasm-GC
`--simd` switches the packed representation to a `TYPE_VBLOCK` over `(array (mut v128))`, so every
*scalar* element access goes through `_v_get`/`_v_set`. With no `linalg:` member intercepted, linalg
paid the new representation and got nothing back (~10% slower). The interpreter's win is the
INTERCEPTION, not the lanes.

## The declined-input protocol -- the one structural difference from `vec:`
`linalg:` defuns also accept boxed arrays, **mixed widths** (NOT an error: the defun widens both and
keeps the FIRST one's width), scalars, two plain numbers, and numpy broadcast. A SAME-width broadcast
pair IS handled by the kernels; a MIXED-width or incompatible pair declines.

**Every kernel is a partial function: it answers "declined" for an input it does not handle, and the
call site then runs the scalar `linalg.lisp` defun.** The library stays the single source of truth
for every edge case, error messages included.

The sentinel is **null**, which works only because compiled nil is `ACONST_NULL` / a wasm null ref
AND none of the intercepted members ever returns nil. `linalg:array-equal`, which does, is
deliberately NOT intercepted. **Anything that would need a second sentinel does not get
intercepted.**
- **The compilers must evaluate each argument form exactly once, into a temp**, because both the
  kernel and the fallback read it (`anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines`,
  `wasmGcSimdLinalgDeclinedInputsRunTheScalarDefun`).
- The tree shakers need no new root: the call site has ordinary call edges to both.
- **Option-form call sites** (`transpose` axes, `:axis`/`:keepdims`): kernels stay POSITIONAL;
  `compiler.LinalgKernelCallLayout.layout`, shared by both codegens so they pattern-match
  identically, maps literal `:keyword value` pairs onto kernel parameters. Anything else routes to
  the ordinary call, where the defun's `&key` prologue signals. Both compilers verify the defun's
  required count still matches and bail to `compileDefault` otherwise; the interpreter has the
  arity-RANGE guard in `LinalgSimd.define` and `LinalgSimd.options`.

## The intercepted set (49 members)
The element-wise arithmetic, reductions, `dot`/`outer`/`transpose`/`reshape`, the unary ufuncs, the
comparison selects `maximum`/`minimum` (strict `(if (> x y) x y)`, never Math.max or a lane min/max
-- the SECOND operand wins any false comparison; CROSS-BACKEND-identical), the five masks, `where`,
`take-rows`, and internal `%la-` members (`LinalgSimd.install` defines each).

A `%`-prefixed member's canonical qualified spelling carries the DOUBLE colon
(`linalg::%la-im2col`), which the interpreter's binding, the compilers' `ctx.functions` keys and the
emit-gate symbol scan must compose (`Jvm`/`WasmLinalgSimdCompiler.qualifiedName`).

Accelerated **transitively**: `rand`/`randn`/`uniform`, `torch:step` over `adam`/`adamw`, `mean`,
`matmul`, `flatten`, `solve`, `square`, `reciprocal`, `clip`, `relu`. **Never** intercepted: `emap`
(arbitrary callback), `det`/`inv`/`solve`'s pivoting (data-dependent pivots), `array-equal`
(sentinel collision), the constructors.

`#'linalg:dot` still names the scalar defun on the compiled backends -- the interception is at the
*call site* there, while the interpreter overrides the *function binding*. Same as `vec:`.

### Per-member notes
- **`%la-im2col`/`%la-col2im`** are pure index arithmetic, bit-identical at both widths.
- **Broadcast, `transpose` axes and the AXIS folds are deliberately SCALAR walks**: the win is
  de-boxing, and scalar double arithmetic is what makes them bit-identical. **Being scalar walks is
  also what made them `--gpu`'s phase-3 member set**; a lane form for the row-broadcast case would
  move that crossover, so rerun the GPU item's `shaped-baseline.lisp` / `StridedCrossover.java`
  first.
- **`%la-matmul-nd`** (`torch.bmm`): **`dot`'s M.M lane loop, once per batch offset**, not a second
  lane loop (all three backends factored the rank-2 product into an offset-taking slab). A BROADCAST
  leading axis is stride 0 and needs no special case. **Precision is a per-batch `linalg:dot`, not
  the defun.** `--gpu` intercepts it on the TOTAL work; a broadcast axis UNDER a non-broadcast one
  declines there and lands back here.
- **`erf`** is `(linalg:emap #'%la-erf-1 a)` and `emap` is never intercepted, while `torch:gelu`'s
  default is built on it. **The kernel is `%la-erf-1`'s own arithmetic in the defun's order,
  bit-identical at both widths** (in DOUBLE, narrowing only on the store). **No lane form, and the
  reason is not "no v128 instruction"**: the per-element iteration count is DATA-DEPENDENT, and on
  aarch64/NEON a lane `exp` is several times SLOWER than the de-boxed scalar loop AND not
  bit-identical to `StrictMath.exp`. **Do not spend time on a masked lane form**; if revisited,
  measure on a machine with 4+ f64 lanes first.
- `abs` and unary minus take `f64.abs`/`f64.neg` once a float operand is established on wasm (the
  generic path left `-0.0` alone where `Math.abs` folds it); the kernel is spelled the same way.

### The optimizer update and the generator (`%la-adam-step` / `%la-rng-fill`)
**The seam intercepts `linalg:` names and nothing else**, and a profile of a GPT training step under
`--gpu --simd` put the remaining cost in two boxed loops outside `linalg:` (`torch::%o-adam-step`
22-31%, `rand`/`randn` 16%). **So the loops moved to `linalg:` rather than the seam widening to
`torch:`.** `torch::%o-sgd-step` was deliberately NOT moved: same shape, on no profile.
- **`%la-adam-step`** carries the rule in an eleven-element packed double vector; `mode` 0/1/2 =
  none / COUPLED (`Adam`) / DECOUPLED (`AdamW`). **`lr * wd` is multiplied by the CALLER** while both
  may still be exact rationals -- storing them separately would round twice and move a run with `:lr
  1/100`. Bit-identical at both widths. **The kernel mutates in place, so every check is up front**:
  a declined call must have written nothing.
- **`%la-rng-fill`**: the generator's state lives in SPECIALS a kernel cannot read, so it is passed
  in as an ARRAY and the end state answered (`%la-rng-state`/`%la-rng-restore` bracket it). The DEFUN
  still calls `%la-rng-next`, so there is one copy of the rule. **The closed form (`s_k = a^k * s mod
  m`) was not needed** -- the cost was a boxed double per draw, not the sequencing; keep it for a
  device kernel. It declines a state outside exact non-negative integers below `2^23`, where `a * s`
  cannot overflow an `int` and Java `%` / wasm `i32.rem_s` agree with Lisp `mod`. **`linalg:seed`'s
  promise makes byte identity non-negotiable.**
- 2.2x per training step on `--gpu --simd`, 1.05x CPU-only. **Do not size the JVM gain from a
  micro-benchmark**: with ONE set of arrays escape analysis scalarizes the boxing away (2% where the
  profile found 22%).

### The selects and copies (eleven members)
A profile put 40% of what was left of a training step in boxed `row-major-aref` walks (`where`
through `torch:masked-fill`, masks, `torch:cat`'s adjoint, `torch:index-select`'s scatter-add,
`torch:clip-grad-norm`, `take-rows`). None is arithmetic, so each is **bit-identical to its defun at
both widths by construction**.
- **The masks**: 1.0/0.0 at the first ARRAY operand's width, the defun's IEEE comparisons on widened
  elements (`-0.0` equals `0.0`, NaN false). NOT symmetric, so scalar-on-the-left runs the reversed
  loop.
- **`where`** is a three-operand broadcast walk, not a materialization (the defun builds three
  broadcast copies first).
- **`%la-gather-strided`'s fifth parameter is a FLAG (nil = double), not the element-type symbol**:
  that is what made it interceptable without a symbol comparison (a quoted symbol is a `String` on
  the JVM, a `$string` offset on wasm, a `LispSymbol` on the interpreter). It computes the walk's
  lowest and highest flat index up front, so a declined call has read nothing.
- **`take-rows` / `%la-scatter-rows`** read the index as the defun (`(truncate (aref idx i))`); the
  range check `v > -1.0 && v < rows` lets `-0.5` truncate to 0 like the defun and declines NaN.
  `%la-scatter-rows` is `torch:index-select`'s backward moved under a `linalg:` name so the seam can
  reach it.
- **`%la-sum-squares` / `%la-scale`** are `torch:clip-grad-norm`'s two loops: a LEFT fold in double
  from the caller's accumulator (byte identity, NOT a lane reduction -- `linalg:sum` of squares would
  follow the reduction contract and move the clip scale). A ratio accumulator or scale declines.
- The wasm `emitBcastShape` takes an explicit decline depth (`br_if 2` is right only at the exit
  block's top level; `where` needs 4).

## What is vectorized, and what is merely de-boxed
Interception is worth it for every member even without lanes: it removes the per-element box and
the generic numeric dispatch, and on the interpreter the whole tree-walking loop.
- **Lane loops**: element-wise arithmetic array-array and with a **double** scalar; `sum`, `norm`,
  `dot` v.v and GEMV (the `vec:` kernels); `dot` v.M / M.M as an `ikj` loop; `outer`;
  `sqrt`/`abs`/`negative`; `reshape`; `maximum`/`minimum` (mask+bitselect on wasm, a scalar select
  on interpreter/JVM -- perf-only, bits identical); `transpose` when BOTH dims are lane-aligned.
- **Scalar loops**: a **single**-float scalar broadcast; `amax`/`amin`/`argmax`/`argmin`/`trace`; the
  transcendentals and `sign`; unaligned `transpose`; the broadcast/axis helpers; `erf`, the
  optimizer, the generator, the selects and copies.

The wasm-GC lane forms for `dot` v.M / M.M / `outer` / `transpose` read each `b` row through
`vec:matvec`'s `i8x16.shuffle` window and accumulate into a **scratch row of the operand width**,
written out through `_v_set` (O(n·p) writes against O(n·m·p) flops). The window's overhang reads
REAL next-row elements but lands only in accumulator lanes past `p`, which the write-out never
reads. **An `#f` scratch row is what makes the matrix product a reduction-contract kernel rather
than a bit-identical one**, and all three backends had to move together.

`norm` is **fused**: the oracle spells `(sqrt (sum (emap square a)))`; every kernel computes
`sqrt(dot(a, a))`.

`amax`/`amin` are deliberately scalar. A lane `MAX` reduce is wrong twice over: the padding lanes are
**zero**, so an all-negative array would answer `0`; and a horizontal fold loses "first strictly
greater wins" (`amaxAndAminKeepTheOracleStrictComparisonSemantics`, plus the all-negative case in
every backend).

## The precision contract
Element-wise results are **bit-identical** to the scalar oracle at both widths. Only reductions move.
- **`array (+) array` at single width computes natively in `float`, and that is exact**: `53 >= 2*24
  + 2`, so widen-compute-narrow yields the correctly rounded `float` for `+ - * /` alike.
- **`array (+) scalar` at single width does NOT enjoy that bound** -- the scalar is a full `double`,
  so those kernels compute in `double` and narrow once. **`(linalg:mul grad 0.1)` over an `#f`
  gradient is the common shape**; splatting `(f32) 0.1` would move its output
  (`aSingleFloatArrayBroadcastAgainstAnInexactScalarStaysBitIdenticalToTheOracle`).
- **Reductions**: an `#f` reduction accumulates in single precision and promotes once at the value
  boundary -- `sum`, `mean`, `norm`, `dot` v.v and M.v.
- **The matrix product follows the reduction contract too**: `dot` v.M / M.M accumulate in the
  OPERAND width in the oracle's ascending `k` order. The defun cannot follow (one float type, f64), so
  oracle and kernel never agree at `#f`; worst case ~3-4% RELATIVE error on a cell that cancelled to
  near zero -- what every f32 GEMM does, PyTorch's CPU `sgemm` included. **Which lanes ran cannot move
  the answer**: lanes go across the output row (`j`), which carries no summation, so all three
  `--simd` backends agree bit for bit.
- **Do not restore the f64 accumulator, which WAS bit-identical**: it forbids lanes (fed only through
  `FloatVector.convert(F2D)`, which on NEON has no intrinsic; `#f` matmul ran 2x slower than `#d`).
  `MatmulFProbe.java` in the GPU item's directory measures the four candidates and **answers
  differently per architecture, so rerun before quoting**; the ranking (f32 lanes, f32 accumulator)
  holds on both. Do not spend time on `convertShape` variants either.
- **`trace`, `amax`, `amin`, `argmax`, `argmin`, `erf`** and the broadcast/axis kernels are
  bit-identical (elements widened to `double`, exactly as the defun).
- **The AXIS folds do NOT follow the lane-reduction contract**: `(linalg:sum a :axis 0)` accumulates
  in f64 from the defun's `0` seed in its order, and the `amax`/`amin` folds mirror `(if (> x acc) x
  acc)` -- the ACCUMULATOR wins ties/NaN, the opposite of the element-wise select, so
  `(linalg:amax #d((-0.0 0.0)) :axis 1)` is `#d(-0.0)` while `(linalg:maximum #d(-0.0) #d(0.0))` is
  `#d(0.0)`. A vector reduced without keepdims returns the f64 accumulator itself (never narrowed),
  and axis `argmax`/`argmin` answer packed DOUBLE arrays at any width -- as the defuns do.
- **`ikj` is bit-identical at double width**: it makes `b[k][*]` contiguous AND visits `k` in the
  oracle's order into the same cell. No transpose, no tiling.

### The probe that pins it
`v = #f(4096.0 1.0 ... 1.0)`, 1024 elements. `4096^2 = 2^24`, where the f32 spacing is 2, so the lane
holding it swallows every `1.0` while the other three fold 256 ones each -> `2^24 + 768`.

| probe | scalar | `--simd` |
|---|---|---|
| `(round (linalg:dot v v))` / `(round (linalg:sum v))` / `(round (* 1024 (linalg:mean v)))` | 16778239 | **16777984** |
| GEMV `(round (aref (linalg:dot (reshape v '(1 1024)) v) 0))` | 16778240 | **16778176** |
| v.M `(round (aref (linalg:dot v (reshape v '(1024 1))) 0))`, and the rank-3 `matmul` | 16778240 | **16777216** |
| any of the above at `#d` | 16778239 | 16778239 |

**The GEMV row moves with `vec:matvec`'s**: linalg's M.v is NOT a kernel of its own (it delegates to
the `vec:` kernel on every backend), so the four-accumulator row moved this probe to `2^24 + 960`.
**Nothing else catches a regression here**: every other `#f` test input stays under `2^24`. Pinned
by `LinalgSimdTest` and `JvmLinalgSimdAccelCompilerTest`
(`singleFloatReductionsAccumulateInSinglePrecisionUnderSimd`),
`WasmLispCompilerIntegrationTest.wasmGcSimdLinalgSingleFloatReductionsAccumulateInSinglePrecision`.

## Float comparison semantics: `>` and the signed-zero surface
`amax`/`amin`/`argmax`/`argmin` compare with `>`; the scalar `>` and the kernels' plain Java `>`/`<`
(not `Double.compare`) must stay in lockstep -- the interpreter's `compareNumeric` has an UNORDERED
state, the JVM picks DCMPG/DCMPL per operator, wasm funnels through `_rat_cmp_bits`.

The signed-zero surface reads identically on interpreter, JVM, wasm-GC, component AND `--no-gc`,
pinned by ci-spec `signed-zero-across-every-float-operator` and
`min-max-nan-is-unordered-so-the-right-operand-wins`. Settled answers (`nz` = `-0.0`): `(+ nz nz)` =
`-0.0`; `(min nz pz)`/`(max nz pz)` keep the LEFT operand on a tie; `(min nan x)` = `x` but `(min x
nan)` = `NaN`; `(signum nz)`, `(sin nz)`, `(tan nz)`, `(tanh nz)` = `-0.0`; `(mod nz 2.0)`/`(rem nz
2.0)` = `-0.0` but `(mod nz -2.0)`/`(rem -4.0 2.0)` = `0.0`; `(eql nz pz)` = `NIL`; `(linalg:amax
#d(-0.0 0.0))` = `-0.0`.
- **`min`/`max` are one rule on both edge axes**: `min(a,b) = (a <= b) ? a : b`, `max(a,b) = (a >= b)
  ? a : b` -- **verified against SBCL bit-for-bit over all 98 ordered pairs from `{-0.0, 0.0, 1.0,
  -1.0, NaN, +inf, -inf}`**, CLHS leaving both unspecified. Deliberately NOT `Math.min`/`f64.min`,
  which resolve the tie by SIGN and propagate NaN from either side.
- **`eql`/`equal` on `-0.0` vs `0.0`** is `NIL` everywhere: wasm compares BITS, OR-ed with a both-NaN
  arm so it stays identical to `Double.equals`.
- **`signum`/`sin`/`tan`/`tanh`** guard the zero (and NaN) case on wasm, whose routes erase the sign;
  the `--simd` kernels (shared by `vec:`, `linalg:` and `--no-gc`) carry the SAME guards, pinned by
  `wasmGcSimdUnaryUfuncsAreByteIdenticalToTheScalarPath` and its `linalg` twin, which fail if only
  one side changes.
- **Float CONTAGION in `min`/`max`**: **SBCL answers `1`** for `(min 1 2.0)`, so there is no
  coercion; the JVM's double fast path is gated on each operand being PROVEN double
  (`JvmLispCompiler.isDefinitelyDouble`, [[jvm-double-arithmetic]]).
- **`--no-gc` is a genuine, permanent exception**: every `+ - * mod rem abs min max` site gets ONE
  static WASM type (`FLOAT` iff any operand is), so `(min 1 2.0)` is `1.0` there. Changing it means a
  dynamic numeric tower, which is the reason `linalg:` cannot compile there at all.

## `mod` / `rem` and the floor family
**A zero remainder is the one CLHS's own definition produces, NOT IEEE `fmod`'s.** The remainder is
`a - b*q` for an exact INTEGER `q`, so **a zero remainder is `-0.0` only when the dividend is `-0.0`
and the divisor is positive, and `+0.0` otherwise**. Interpreter and JVM keep Java's exact `%`/`DREM`
and re-derive only the zero (`_frem`, which `_fmod` calls first, so `mod` and `rem` share one zero).

**The magnitude is NOT the f64 evaluation of that formula, and SBCL is not the oracle for it.** `a -
b*q` has ONE right value, always representable; f64 evaluation rounds twice (`(rem 1d18 7.0)` is
`1.0` here and `0.0` on SBCL). CLHS decides the SIGN, mathematics the magnitude. Both wasm backends
reach the exact `fmod` through `codegen/wasm/WasmFmodRuntimeBuilder` (the wasm-GC float arm, and
INLINED by `NoGcWasmCompiler.compileModRem`) -- **one builder, so the two cannot drift**. A zero
divisor, a NaN or an infinite dividend signals as `floor` does (error-handling.md, "Per operator");
`NaN` on `--no-gc`.

**Trap: `mod`'s sign correction must not multiply the operands.** `r * b < 0` UNDERFLOWS to zero
when both are tiny, so the correction silently does not fire and `mod` answers `rem`'s negative
remainder. Compare the two signs directly.

**The QUOTIENT is exact at every magnitude too**: all four backends divide the operands AS THE EXACT
RATIONALS THEY ARE and round that, giving a bignum when needed (not the double `a/b` narrowed into a
`long`, which rounds twice and CLAMPS). **SBCL is not the oracle for the two-argument rows** (it
rounds `a/b` in f64 first); the one-argument rows match. Not confined to huge magnitudes: `(floor
1.0 0.1)` is `9` with remainder `0.09999999999999995`, because the double `0.1` is a shade above a
tenth.

The second value: `LispMacroExpander.lowerMvProducer`, the ONE lowering all four share, reads each
remainder off `rem` and `mod`: `truncate` `(rem a b)`, `floor` `(mod a b)`, `ceiling` `(- (mod (- a)
b))` (negating the DIVIDEND -- subtracting a divisor from `mod` would round twice and lose a tiny
dividend; a zero takes `rem`'s zero so the negation does not flip the sign), `round` whichever its
quotient landed on. Per backend: `eval/ExactRounding`, the JVM's `_fdiv`/`_frat`
(`JvmNumericRuntimeBuilder`), wasm-GC's `_f64_fdiv` (`WasmFloatFdivRuntimeBuilder`, over the
limb-tier `_big_fdiv`). All DECLINE to the f64 route for a ratio operand and a non-finite dividend.

**Two integers divide with no ratio made**: the interpreter's `ExactRounding.quotient` integer arm;
the JVM's `_iquo` (two `Long`s by `LDIV` and a one-step sign fix, anything wider `_iquox`); wasm-GC
divides two i31s INLINE at the site (one `i64.div_s`), with the same i64 arm in `_big_fdiv` for the
boxed tier. Measured 5-10x per call on both (JVM `(floor x 7)` 186 -> 22 ns, wasm 58 -> 9 ns). The
inline wasm arm beats a call into `_big_fdiv` (24-27 ns). Integer fusion does not take
`floor`/`truncate` (`.kb/jvm-int-fusion.md`): the JVM loop matches the fused `mod` (`_iquo` inlines);
on wasm Cranelift turns a division by a literal into a multiply ("Quotients of two integers",
`.kb/wasm-int-fusion.md`).

**An INFINITE divisor is settled by sign, not declined**: with a finite nonzero dividend `a/b` is an
infinitesimal, so `truncate`/`round` are `0` and `floor`/`ceiling` `0`/`1` when the signs agree,
`-1`/`0` when not. An exact-zero dividend still takes the old route.

**A non-finite QUOTIENT signals**: a NaN or infinite argument, or an `a/b` that is one, has no
integer, so every backend signals `ClosRegistry.NON_FINITE_ROUNDING_MESSAGE` (wasm traps outside EH
mode) instead of clamping (`(floor inf)` was `9223372036854775807`; a NaN slipped past `DCMPL` into
`D2L`'s `0`). The `f` variants signal with their integer twin (SBCL answers `ffloor` of an infinity
but signals on `fround`; ECL signals on all four -- the twin rule is kept).
`decode-float`/`integer-decode-float`/`rationalize` test `(= (- x x) 0.0)` (false for exactly NaN and
infinity) and signal -- they used to loop forever. Programs naming none are byte-identical. Pinned by
ci-spec `rounding-or-decoding-an-infinity-or-a-nan-signals` and
`roundingOrDecodingAnInfinityOrANanSignals*` in `LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest`.

Pinned by ci-spec `mod-rem-zero-is-the-truncate-and-floor-remainder`,
`mod-rem-are-the-exact-float-remainder-at-any-magnitude`,
`the-floor-family-quotient-is-exact-at-any-magnitude`;
`LispEvaluatorTest.theFloorFamilySecondValueIsTheRemainderCLHSDefines` (an exact-rational oracle
sharing no code with the implementation); and per-backend 240-pair DIFFERENTIALS against the
interpreter crossing 2^53, 2^63, the subnormals and both signed zeros
(`theFloatRemainderMatchesTheInterpreterOverAMagnitudeSweep`,
`theFloorFamily{MatchesTheInterpreterOverAMagnitudeSweep,QuotientWithAnInfiniteDivisorMatchesTheInterpreter}`).

**`--no-gc` cannot follow and is the one documented divergence**: i64-native with no bignum tier
([[wasm-bignum]]), so `(floor 1d300)` TRAPS and `(truncate 1d18 7.0)` keeps the rounded-double
quotient; `(floor -3.0 inf)` stays `0`. The remainder side is unaffected. The `f` variants do not
exist there.

## Per-backend mechanics
**Interpreter.** `LinalgSimd.install` runs right after `linalg.lisp` is evaluated, guarded by
`this.simd`. Each override **captures the defun it replaces** and applies it on decline
(`LispEvaluator.applyGlobal`). `LinalgSimd.available()`/`install(...)` are the ONLY entry points into
`LinalgSimdKernels`, which is what makes `src/web/java/.../Target_LinalgSimd.java` sufficient to cut
the Vector API out of the Web Image. **A new public method on `LinalgSimd` that touches the kernels
would break it, and only the Pages workflow's Web Image build would notice**; `./mvnw -Pweb compile`
is the local check.

**JVM.** **One bridge class** (`JvmSimdVectorTemplate`), so one `_simdInit` and one
`resource-config.json` entry -- a second template class would need its own entry and fail at RUN
time. Method refs are registered under **package-prefixed keys** (`"linalg:add"`), because `vec:add`
and `linalg:add` share a member name. **`jdk.incubator.vector` is an optional module**: a class
constant only LOADS the bridge, so `_simdInit` forces linking with
`MethodHandles.lookup().ensureInitialized` inside its `LinkageError` catch -- with a bare `ldc` a JVM
without `--add-modules` dies with `NoClassDefFoundError: jdk/incubator/vector/Vector` at the first
kernel call. So every call site checks `_simdReady()` BEFORE emitting a call that would resolve into
the bridge (`JvmSimdModuleFallbackTest`, a child JVM with no `--add-modules`). An element-wise linalg
kernel is the `vec:` one at a different header offset, and a fresh result copies the whole header
(`laNewLike`). The gate scans AFTER `LinalgLibrary.process`, so ANY linalg program ships the bridge.

**wasm-GC.** Standalone functions at `WasmLispCompiler.linalgFuncBase()`, emitted only under
`--simd`. Scratch lives in fresh `$hash_buckets` i31 arrays -- kernels cannot hold extra local groups
beyond the fixed `WasmVecSimdRuntimeBuilder.withLocals` order, which is also why they must be
standalone. `WasmLispCompilerTest.simdAppendsExactlyTheVecTypeBlockAndTheVecAndLinalgFunctionBlocks`
pins the delta -- the only structural guard that a build WITHOUT `--simd` stays byte-identical.
**Update it, never weaken it.**

Kernel structure: a `block` per shape, `br` to the outermost to decline; `ref.test (ref $farray)` on
nil is false, so a nil argument declines for free. Three things `vec:` kernels did not need:
- **Compare dims, not just counts** (`emitDimsEqual`): `(2 3)` and `(3 2)` have the same count but
  the defun errors.
- **Copy dims into the result**, copied not shared, matching `%la-like`.
- **Restore the last group's padding after a broadcast.** Over zero padding `0 - s = -s`, `s / 0 =
  inf`, `0 / 0 = NaN` (why array-array `div` needs it too); a later `sum` would fold the garbage in.

New v128 opcodes go in `am.ik.wasm.Instruction` and `WasmSections.skipSimd` (which throws on an
unknown `0xFD` sub-opcode, by design).

## Verification
- `eval/LinalgSimdTest` -- the interception guard discriminates by TYPE (`#'linalg:add` is a
  `LispFunction` under `--simd`, a `LispLambda` without; both print the same), byte identity vs the
  oracle at both widths and ranks, the declined inputs, the f32 probe,
  `erfMatchesTheScalarOracleOverTheWholeRangeAtBothWidths`,
  `theAdamStepAndTheGeneratorFillAreInterceptedUnderSimd`,
  `theSelectsAndCopiesAre{InterceptedUnderSimd,BitIdenticalToTheScalarOracleAtEveryShapeAndWidth}`,
  `...DeclineWhatTheDefunSignalsAndSignalItUnchanged`. Test-writing caveats: a long-fill case
  compares the ARRAY, not a `linalg:sum` of it (`sum` is itself a lane reduction under `--simd`), and
  the `torch:` tape case uses `torch:mul` rather than `torch:matmul` (an f32 MATMUL is not
  byte-identical under `--simd`).
- `codegen/jvm/JvmLinalgSimdAccelCompilerTest` -- the dead-flag guard, byte identity, the
  evaluate-once guards (base AND extended call sites), the library errors still signalling.
- `codegen/wasm/WasmLispCompilerIntegrationTest` `wasmGcSimdLinalg*`, including
  `...LaneProductsMatchTheScalarPathAtEveryRowLaneOffset` (every shuffle-offset variant, the odd-`p`
  sentinel-group write, a next-row inf inside the window overhang).
- `examples/ml/{tiny-llm,linear-regression,heat3d,deep-digits}.lisp` print byte-identical output
  with and without `--simd`.

## Not done
- Lane-unaligned `outer`/`transpose`, `amax`/`amin`/`argmax`/`argmin`/`trace` and the single-float
  scalar broadcast keep element loops; a blended-edge lane form was judged not worth the shuffle
  bookkeeping.
- A linalg program dominated by `emap`/`inv` still runs slower under `--simd` on wasm-GC. The penalty
  is **intrinsic**: a `v128` can only be read out of an `(array (mut v128))`, so no representation is
  fast for both lane loops and scalar access. Uncosted alternative: keep `(array (mut f64))` and
  gather lanes with `array.get` + `replace_lane` per group (maybe 2-4x the kernel cost, no
  un-intercepted regressions); measure before believing either.
- An `emap` special case for a *known builtin* `f` (`tiny-llm.lisp`'s `#'silu` is a user lambda and
  would not benefit).
