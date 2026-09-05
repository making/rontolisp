# The `linalg` package (numpy-style vector/matrix operations)

One hand-written Lisp-source library, `src/main/resources/am/ik/rontolisp/eval/linalg.lisp`,
following the `json.lisp` pattern ([[json]]) so one implementation runs identically on all
backends. 91 exported functions over the built-in arrays. Computes in packed float (speed over
exactness), DOUBLE by default but WIDTH-POLYMORPHIC: a constructor opts into packed single-float
(`#f`) with `:element-type`, and every transform PRESERVES its input width.

Rank rules: elementwise ops, reductions, `reshape`/`flatten`, `array-equal` and `diff` walk
`row-major-aref` and are rank-generic; `matmul` is rank-generic (rank >= 3 = the numpy stacked
product); `dot`/`outer`/`det`/`inv`/`solve`/`trace`/1-arg `transpose` are rank <= 2 only;
`gradient` is vectors only.

Options are numpy-style `&key`, never trailing positionals (`:element-type` on every constructor,
`:axis`/`:keepdims` on reductions, `:n`/`:axis` on `diff`); the only positionals are the ones
numpy takes positionally (`arange`'s start/stop/step, `transpose`'s axes list, `gradient`'s
spacing). **Trap: the `--simd` interceptors pattern-match the LITERAL keywords at a call site**
(`compiler.LinalgKernelCallLayout`, shared by both codegens; `LinalgSimd.options` on the
interpreter), so a spliced body forwarding an option (`mean` -> `sum`) must spell it literally.

## API surface
Inputs are ordinary built-in arrays (`#(...)`, `#2A(...)`, `#nA(...)` via `Token.ArrayOpen`).
**linalg RESULTS are packed double-float arrays (`LispDoubleFloatArray`) printing `#d(...)` at
every rank** -- an example pinning linalg output must expect `#d(...)`, not `#(...)` (`#f` =
single). Stay in `cl-user` and call qualified names (the package does not use `cl`);
`#'linalg:name` works. Full per-function semantics: `doc/*/reference/functions/linalg-*.md`.

- Constructors: `zeros`, `ones`, `full`, `eye`, `arange`, `linspace`, `from-list`/`to-list`,
  `zeros-like`, `one-hot`.
- Shape: `shape`, `ndim`, `size`, `reshape` (one extent may be `-1`), `flatten`, `transpose`
  (optional axes permutation), `pad`, `expand-dims`, `squeeze` (squeezing every axis returns the
  ELEMENT -- linalg has no rank-0 arrays), `concatenate` (existing axis), `stack` (new axis),
  `slice` (numpy BASIC slicing, axes KEPT), `triu`/`tril`.
- Elementwise: `add`/`sub`/`mul`/`div` with numpy broadcasting (`mul` is Hadamard, NOT matrix
  product) and their n-ary LEFT-FOLD aliases `+`/`-`/`*`//` (degenerate arities follow CL; each
  fold step is a LITERAL `linalg:add` call so `--simd` still intercepts); `emap` (never
  intercepted); the named ufuncs `exp`/`sqrt`/`abs`/`square`/`negative`/`sign`/`reciprocal`
  (interceptable); `power` (not intercepted, no `expt` kernel).
- Activations: `softmax`/`log-softmax` (max-subtracted; no `:axis` = whole array, scipy default;
  `log-softmax` is `(x-m) - log(sum(exp(x-m)))`, NOT `(log (softmax x))`, so a zero weight gives
  -inf not NaN); `erf` via the all-positive-term series A&S 7.1.6 (`%la-erf-1`), NOT the
  alternating Maclaurin series, whose cancellation loses every digit by |x| ~ 3; exactly +-1
  beyond |x| = 6. No `erfc` -- `(sub 1.0 (erf a))` is it.
- Products: `dot` (numpy dispatch, SIGNALS at rank >= 3), `matmul` (rank <= 2 via `dot`, else the
  stacked product), `outer`, `trace`, `norm`, `det`/`inv`/`solve` (Gaussian elimination with
  partial pivoting in double; a singular `det` may be a small epsilon, `inv` errors).
- Reductions: `sum`/`mean`/`var`/`std` (`:ddof` 0 default = np.var), `amax`/`amin` (strict fold,
  first wins ties, NaN never replaces the seed), `argmax`/`argmin`, `diff`, `gradient`.
- Selection: `equal`/`greater`/`greater-equal`/`less`/`less-equal` (0.0/1.0 MASK), `where`
  (elementwise SELECT -- what keeps an infinite operand from becoming NaN), `take-rows` (axis 0
  KEPT), `row` (axis 0 DROPPED, rank >= 2 only), `gather`, `array-equal` (arrays themselves are
  `eq`-compared, hence the need).
- RNG: `seed`, `rand`, `randn`, `uniform`, `choice`, `permutation`.

## Internal members that exist for `torch:` only
Nothing in the numpy surface reaches these; the `--simd` seam intercepts `linalg:` members and
nothing else, so a loop `torch.lisp` would otherwise spell inline as boxed `row-major-aref` walks
becomes a member here ([[gpu]], [[linalg-simd]]).
- `%la-scatter-rows` (`take-rows`' adjoint, in place), `%la-sum-squares`, `%la-scale`,
  `%la-adam-step`.
- FUSED compositions: `%la-softmax-grad`, `%la-log-softmax-grad`, `%la-gelu`, `%la-gelu-grad`,
  `%la-layer-norm`, `%la-layer-norm-grad`, `%la-layer-norm-grad-norm`, `%la-dropout-mask`,
  `%la-scaled-masked-softmax`, `%la-scaled-masked-softmax-grad`, `%la-layer-norm-affine`,
  `%la-layer-norm-affine-grad`. **These have NO kernel of their own on the CPU seams**: each defun
  is the exact chain of `linalg:` members `torch.lisp` used to spell, member for member and in the
  tape's own order, so every CPU path produces the bits it always produced and only `--gpu`
  intercepts the member itself ([[gpu]], [[torch]]). `%la-dropout-mask` advances its state vector
  `st` IN PLACE; the width rides as the `single` flag.
- Two members answer TWO arrays as a two-element LIST (`%la-layer-norm-affine-grad` -> `(dx gn)`,
  `%la-layer-norm-grad-norm` -> `dx` plus `norm`). A LIST rather than a new call shape
  deliberately: an extra RESULT is not an extra argument, so the arity stays five and every seam
  carrying a five-argument member carries it unchanged.

## Rank-N: two internal walks
Everything strided bottoms out here, so there is exactly one place a strided read can be wrong.
- `%la-gather-strided (a od rs base single)` -- fill a fresh `od`-shaped array by walking `a`'s
  flat row-major index from `base` by the INNERMOST-FIRST strides `rs` through the
  `%la-bcast-loop` odometer. `linalg:slice` builds `rs` from `step * axis-stride`;
  `%la-broadcast-to` builds it from `%la-bcast-strides` (stride 0 on a stretched axis). The width
  rides as a FLAG (nil double, non-nil single) because a flag is what every kernel can read
  without a symbol comparison.
- `%la-matmul-nd` -- the batched product; `%la-batch-strides` is `%la-bcast-strides` with a non-1
  innermost stride, **which is the whole difference between broadcasting an ELEMENT and
  broadcasting a MATRIX**. Rank-2 `%la-matmul` stays the fast path. `%la-matmul-nd-ta`/`-tb` are
  the same product with one operand's last two axes exchanged (the shape both matmul adjoints
  have) -- portably `%la-swap-last` plus the product, so nothing about the VALUE depends on which
  ran; they exist so an accelerator can read the operand in the orientation it is ALREADY stored
  in ([[gpu]]). Two arity-2 members rather than one with flags, so existing seams carry them
  unchanged.
- `%la-matmul-nd` IS `--simd`-intercepted (its precision contract is a per-batch `linalg:dot`, not
  this defun); the DISPATCH, the scalar rejection and both error messages stay in the library. No
  other rank-N addition is intercepted.
- `linalg::%la-im2col` / `%la-col2im` (rank-4, direct index arithmetic, backing the
  deep-learning-from-scratch CNN examples) ARE intercepted -- not with lanes but as native
  kernels, because the boxed loop dominated ~97% of a ch07 train run once the matrix product was
  accelerated.

## `-inf` through `where` -> `softmax`
Masked attention is `(linalg:where mask score -inf)` then `softmax`, and it works only because
`where` SELECTS: the older multiply-by-a-0.0/1.0-mask spelling turns `0.0 * -inf` into `NaN`.
It was not free. `WasmExpCompiler`'s software `exp` is a degree-5 Taylor polynomial on
`t = x / 256` followed by 8 squarings, and that polynomial has a real root near `t = -2.18`
(`x = -558`) below which `p(t)` is NEGATIVE and an even number of squarings makes it hugely
POSITIVE -- `(exp -1000)` was `2.4e125`, so a masked softmax returned `NaN` on WASM. A large
finite negative mask would NOT have been safer. Fix: one instruction, `f64.max(p(t), 0.0)` before
the squarings (`WasmExpCompiler.UNDERFLOW_CLAMP`, mirrored in
`WasmVecSimdRuntimeBuilder.emitExpF64` so `--simd`/`--no-gc` kernels stay bit-identical). NaN
still propagates, `+inf` untouched. Rest of the WASM transcendental contract: [[vec]].

`softmax`/`log-softmax`/`erf` live here rather than in the differentiable layer because a second
copy would fork the array math; `erf` exists for the EXACT GELU `x * (1 + erf(x/sqrt 2)) / 2`
([[torch]]). Its accuracy depends on `exp`, so on WASM it inherits that backend's software `exp`.

## Seeded RNG (the np.random analog)
Wichmann-Hill: three multiplicative congruential generators (`%la-rng-s1/-s2/-s3`, moduli
30269/30307/30323), period ~6.95e12. Every intermediate stays below 2^23 (inside i31) and each
draw is exact integer arithmetic plus IEEE `+ - * /` on exact operands, so **a SEEDED SEQUENCE IS
BIT-IDENTICAL ON ALL FOUR BACKENDS**. `randn` is Irwin-Hall (12 uniforms minus 6), NOT
Box-Muller: WASM's `log`/`cos` are polynomial approximations that would break that identity;
tails clip at +-6 sigma. `linalg:seed` discards ~10 draws so nearby seeds decorrelate.
Deliberately no dependence on the builtin `(random n)`.

`rand`/`randn`/`uniform` share ONE fill loop `%la-rng-fill (out st mode lo span)`, taking the
state as an ARRAY and answering the state it ends on as one -- that is what makes it a pure
function and so interceptable. The three specials are its scratch (`%la-rng-state` /
`%la-rng-restore`); the rule lives only in `%la-rng-next`, which the scalar draws (`%la-rng-int`,
hence `choice`/`permutation`) keep using directly. `mode` picks the element rule (0/1/2).

## Single-float / width polymorphism
Double by default but accepts and preserves packed single-float, so a `#f` value flowing in from
`vec:` is never silently widened (the widening would force a mixed-width `--simd` error on the
next `vec:matvec`). Two orthogonal mechanisms:
- Constructor opt-in via `:element-type` on every constructor. `arange` is the one signature whose
  POSITIONAL count varies and CL's `&optional` greedily eats a following keyword, so it is
  `(&rest args)` split by `%la-split-element-type`.
- Width-following transforms (`add`/`sub`/`mul`/`div`/`emap` via `%la-like`,
  `transpose`/`reshape`/`flatten`, `dot`/`matmul`/`outer`, `inv`/`solve`) preserve the first array
  input's width. Automatic, no API change.

The seam is `%la-make (dims init &optional element-type)`, whose two branches take a LITERAL
`:element-type`, so every backend picks the `double[]`/`float[]` (`TYPE_F64ARR`/`TYPE_F32ARR`)
repr statically; a runtime-computed element-type could not. `%la-etype` returns the literal symbol
matching a width (a boxed array reads back as `t`). No reader conditional is needed -- unlike
`vec::%make-like`, whose double-only-on-wasm split is now vestigial (`vec:` still renders a `#f`
elementwise result as `#d` on wasm-GC while linalg renders `#f` everywhere). A cross-backend `#f`
pin must use f32-EXACT values (integers/halves).

## `--simd` acceleration
Twenty members are intercepted on the interpreter, the JVM and wasm-GC, reusing the `vec:` lane
loops: `add sub mul div sum norm amax amin argmax argmin trace transpose reshape dot outer exp
sqrt abs negative sign`. `mean`/`matmul`/`flatten`/`solve`/`square`/`reciprocal` ride along
through the members their bodies call. `emap`, `det`, `inv`, `array-equal`, `diff` and `gradient`
are never intercepted.

**linalg.lisp stays the ORACLE and is never rewritten to suit a kernel.** Each kernel is a PARTIAL
function returning null for an input it does not handle (boxed array, mixed widths, plain number,
ratio scalar, shape mismatch); the call site then runs the scalar defun, which supplies the exact
behavior and error message. Elementwise results are bit-identical to the oracle at both widths;
only reductions move (an `#f` reduction accumulates in single precision). The matrix product is
exempt. Full mechanics and the `-0.0` cross-backend footgun: [[linalg-simd]].

## Wiring
- Package registered in the `PackageRegistry` constructor; exported names in
  `PackageRegistry.LINALG_FUNCTIONS` (`linalgFunctionNames()`). It does not use `cl`, so inside
  `(in-package linalg)` standard names need `cl:`. Adding a function = the name there + a defun +
  per-operator doc pages (en and ja).
- Driver `am.ik.rontolisp.eval.LinalgLibrary`, a simplified `JsonLibrary` mirror with NO call-site
  rewriting: `process(program)` only detects usage and prepends `forms()`.
- Interpreter: `LispEvaluator.resolveFunction` falls back on a missed `linalg:`-qualified lookup
  to evaluating `LinalgLibrary.forms()` once and retrying; `#'linalg:name` uses the same path.
- Compile path: `RontoLispCli.compileToFile` and the playground wrap the program as
  `LinalgLibrary.process(JsonLibrary.process(...))`. Compiler unit tests must call
  `LinalgLibrary.process` explicitly (`compileAndRunLinalg` helpers).
- Native image: registered in `resource-config.json` (typeReachable `LinalgLibrary`).

## Source constraints (linalg.lisp)
- Canonical package shape, so resolving it is a fixed-point no-op -- pinned by
  `PackageResolverTest.linalgLibraryFormsAreAResolverFixedPoint`.
- `%la-bcast`'s broadcast lambdas capture the operator and scalar operand, so its parameters use
  `%la-` names.
- Flat iteration uses `row-major-aref` / `(setf (row-major-aref ...))` directly, which is what
  makes the elementwise ops rank-generic.
- numpy general broadcasting: `%la-bcast` sends equal shapes to the flat loop and everything else
  to `%la-bcast-loop`, an odometer over stride lists (`%la-bcast-strides` pads a stretched/missing
  axis with stride 0; `%la-bcast-shape` aligns trailing axes and signals "linalg: shape mismatch"
  otherwise). The `--simd` kernels are untouched: a broadcast pair has unequal dims, which every
  kernel already declines.
- Results are fresh arrays (inputs are never mutated). Non-terminating results print at fewer
  significant digits on WASM, so cross-backend-deterministic output should stick to integer-valued
  or short-terminating decimals -- ci-spec `linalg-package-cross-backend` uses power-of-two
  matrices for `det`/`inv`/`solve`; `linalg-single-float-cross-backend` pins the `#f` path.
- Not supported: `--no-gc` (arrays), runtime `eval` of linalg forms (the emitted eval runtime has
  no array ops).
- Worked idioms: `examples/ml/linear-regression.lisp`, `deep-digits.lisp`, `heat3d.lisp`.

## Standard array functions added alongside
`array-dimensions` and `row-major-aref`/`%row-major-aset` are backend primitives (interpreter
`Environment.registerArrays`; `JvmArrayRuntimeBuilder`, where row-major access reuses
`_aref1`/`_aset1` because the data is stored flat right after the header; `WasmArrayCompiler`).
Everything else is `LispMacroExpander` expansion over existing primitives: `vector`, `svref` (also
a setf place sharing the `%aset` case),
`array-rank`/`array-dimension`/`array-total-size`/`array-row-major-index`, and `coerce`. **Trap:
the JVM helper gating (`JvmLispCompiler.programUsesAnyArrayOp`) must list the derived names too,
because the scan runs before expansion.** None are first-class function values.
