# The `linalg` package (numpy-style vector/matrix operations)

One hand-written Lisp-source library, `src/main/resources/am/ik/rontolisp/eval/linalg.lisp`,
following the `json.lisp` pattern (`json.md`) so one implementation runs identically on all
backends. 91 exported functions over the built-in arrays. Computes in packed float (speed over
exactness), DOUBLE by default but WIDTH-POLYMORPHIC: a constructor opts into packed single-float
(`#f`) with `:element-type`, and every transform PRESERVES its input width.

Elementwise ops, reductions, `reshape`/`flatten`, `array-equal` and `diff` walk elements via
`row-major-aref`, so they are rank-generic; `matmul` is rank-generic (rank >= 3 = the numpy
stacked product); `dot`/`outer`/`det`/`inv`/`solve`/`trace`/1-arg `transpose` are rank <= 2 only;
`gradient` is vectors only.

Options are numpy-style `&key`, never trailing positionals: `:element-type` on every
constructor, `:axis`/`:keepdims` on reductions, `:n`/`:axis` on `diff`. The only positional
options are the ones numpy takes positionally: `arange`'s start/stop/step, `transpose`'s axes
list, `gradient`'s spacing. Trap: the `--simd` interceptors pattern-match the LITERAL keywords at
a call site (`compiler.LinalgKernelCallLayout`, shared by both codegens; `LinalgSimd.options` on
the interpreter), so a spliced body forwarding an option (`mean` -> `sum`) must spell the keyword
literally.

## API quick reference

Inputs are ordinary built-in arrays: rank-1 `#(...)`, rank-2 `#2A(...)`, higher `#nA(...)` (the
reader parses `#nA(...)` via `Token.ArrayOpen`). linalg RESULTS are packed double-float arrays
(`LispDoubleFloatArray`) printing with `#d(...)` at every rank -- an example pinning linalg
output must expect `#d(...)`, not `#(...)` (`#d` = double packed prefix, `#f` = single-float).
Read/write with `aref` / `(setf (aref ...))`. `shape` = an integer `n` or a list of dimensions.
Stay in `cl-user` and call qualified names (the package does not use `cl`). `#'linalg:name` works
(plain defuns). Errors signal via `error`.

| Function | Semantics |
| --- | --- |
| `zeros shape &key element-type` / `ones` / `full shape v` | filled with 0 / 1 / v; `:element-type 'single-float` for `#f` |
| `eye n` | n x n identity |
| `arange stop` / `(start stop)` / `(start stop step)` + `:element-type` | vector, stop exclusive, step may be negative; `&rest`-parsed so the keyword may follow any positional count |
| `linspace start stop n` | n evenly spaced values, inclusive |
| `from-list lst` / `to-list a` | flat list -> vector, list of equal-length rows -> matrix; and the inverse |
| `shape a` / `ndim a` / `size a` | dims list / number of dimensions (0 for a plain number) / element count |
| `reshape a shape` | row-major copy; error if sizes differ; ONE extent may be `-1` and is inferred, a bare `-1` flattens |
| `zeros-like a` / `flatten a` | fresh zeros with a's shape AND width / rank-1 row-major copy |
| `transpose a &optional axes` | matrix transpose (a vector unchanged). With an axes list: the rank-n permutation, out-dims[k] = dims[axes[k]], each axis named once. Only the 1-arg matrix form is `--simd`-intercepted |
| `pad a pads` | constant-0 padding (np.pad default): one `(before after)` pair per axis, or one non-negative integer for both sides of every axis; keeps a's width |
| `expand-dims a axis` | copy with an extent-1 axis inserted (np.expand_dims / torch unsqueeze); negative axis counts from the end of the RESULT, so -1 appends. Row-major order unchanged, so it is a `reshape` |
| `squeeze a &key axis` | extent-1 axes removed (np.squeeze): all with no `:axis`, else the integer (or list of) axes named -- a named axis of extent != 1 signals. Squeezing away EVERY axis returns the ELEMENT (linalg has no rank-0 arrays) |
| `concatenate arrays &key axis` | LIST joined along an EXISTING axis (np.concatenate / torch.cat; default 0, negative from the end). Equal ranks, equal extents off the axis. Width of the first input |
| `stack arrays &key axis` | LIST joined along a NEW axis (np.stack): equal shapes, rank + 1, new axis of extent `(length arrays)`; negative counts from the end of the RESULT |
| `slice a specs` | numpy BASIC slicing, one spec per axis: `nil` = whole axis, or `(start end)` / `(start end step)`. Negative index from the end, `nil` start/end = from the beginning / to the end, negative step walks backwards, a MISSING trailing spec leaves that axis whole. Axes are KEPT; dropping one is `row`. Runs on `%la-gather-strided` |
| `triu a &key k` / `tril` | copy with everything below / above the k-th diagonal zeroed (default k = 0). Rank >= 2; a stack is masked on its LAST TWO axes. `(triu (ones '(n n)) :k 1)` is the causal attention mask |
| `add a b` / `sub` / `mul` / `div` | elementwise with numpy broadcasting (scalar on either side; arrays aligned on trailing axes, extents equal or 1, missing leading axis = 1, else the shape-mismatch error); keeps the first array operand's width; `mul` is Hadamard, NOT matrix product |
| `+ &rest` / `- a &rest` / `* &rest` / `/ a &rest` | n-ary LEFT FOLDS of add/sub/mul/div as plain defuns. Degenerate arities follow CL: no argument is 0 / 1; one argument to `+`/`*` is itself; one to `-`/`/` is `(sub 0 a)` / `(div 1 a)`. Each fold step is a LITERAL `linalg:add`/... call, so `--simd` still intercepts inside the alias |
| `emap f a` | fresh array with f applied to every element (never `--simd`-intercepted) |
| `exp` / `sqrt` / `abs` / `square` / `negative` / `sign` / `reciprocal` | named unary ufuncs = `emap` of the obvious scalar op (`square` = `mul a a`, `reciprocal` = `div 1 a`); unlike `emap` they ARE interceptable |
| `power a b` | elementwise `a ** b` through `%la-bcast`; not intercepted (no `expt` kernel) |
| `softmax a &key axis` / `log-softmax` | max-subtracted softmax and its log: no `:axis` = the whole array is one distribution (scipy default), an integer `:axis` = one per slice (torch `softmax(x, dim)`). `log-softmax` is `(x - m) - log(sum(exp(x - m)))`, NOT `(log (softmax x))`, so a zero weight gives -inf, not NaN |
| `erf a` | Gauss error function (scipy.special.erf). Accurate to a double's last ulps over the WHOLE range via the all-positive-term series A&S 7.1.6 (`%la-erf-1`), NOT the alternating Maclaurin series, whose cancellation loses every digit by \|x\| ~ 3; exactly +-1 beyond \|x\| = 6, which also bounds the loop. No `erfc`: `(sub 1.0 (erf a))` is it |
| `dot a b` | numpy dispatch: vec.vec -> scalar, mat.vec / vec.mat -> vector, mat.mat -> matrix product; a scalar operand multiplies elementwise. Rank >= 3 on either side SIGNALS (`linalg: dot expects rank <= 2 ...`) |
| `matmul a b` | matrix product (also mat.vec) at rank <= 2 via `dot`; at rank >= 3 on EITHER side the numpy STACKED product (`%la-matmul-nd` = torch.bmm/matmul): last two axes are the matrix, leading axes broadcast, a rank-1 operand is promoted (row left, column right) with its axis dropped again. Rejects scalar operands at every rank |
| `outer u v` | outer product (inputs flattened first) |
| `sum a &key axis keepdims` / `mean` | no axis: over all elements (a packed/float array reduces to a double, a plain integer array to an integer/ratio; non-nil keepdims wraps the scalar in an all-ones-shape array). Integer axis (negative from the end): reduce along it, axis dropped -- kept as extent 1 under keepdims; a vector without keepdims reduces to the scalar. Any rank |
| `var a &key axis keepdims ddof` / `std` | variance / stddev, whole-array or along an axis, divided by `n - ddof`: `:ddof 0` (default) = np.var and torch `unbiased=False`, `:ddof 1` the sample variance. `std` is `cl:sqrt` on a scalar result, `linalg:sqrt` on an array. `mean` + `std` along one axis is LayerNorm |
| `amax a &key axis keepdims` / `amin` | largest / smallest, whole-array or along an axis; strict-comparison fold (first wins ties, NaN never replaces the seed); error on an empty array or axis |
| `argmax v &key axis` / `argmin` | no axis: index in a VECTOR (first on ties). With an axis: per-slice indices, axis dropped; rank >= 2 results are a packed DOUBLE array of index values, a vector reduces to the integer index |
| `norm a` / `trace a` | Euclidean/Frobenius norm / main-diagonal sum (square matrices only) |
| `diff a &key n axis` | n-th discrete difference along `:axis` (np.diff; default n = 1, axis -1, negative from the end; each step shortens that axis by one, clamped at 0); any rank; n = 0 returns a packed copy |
| `gradient f [spacing]` | np.gradient over a VECTOR: second-order central differences inside, first-order one-sided at the ends, same length; spacing = uniform scalar (default 1) or a same-length coordinate vector; needs >= 2 samples |
| `det a` / `inv a` / `solve a b` | Gaussian elimination with partial pivoting in double; a singular matrix's `det` may be a small epsilon rather than 0; `inv` errors on singular; `solve` solves a.x = b for vector or matrix b |
| `array-equal a b` | same shape + numerically equal elements (1 = 1.0); needed because arrays themselves are `eq`-compared |
| `equal` / `greater` / `greater-equal` / `less` / `less-equal` | elementwise comparison as a 0.0/1.0 MASK of the first array operand's width; scalars and broadcasting via `%la-bcast`; multiply by the mask where numpy would boolean-index |
| `where mask x y` | elementwise SELECT (np.where): x where mask is non-zero, y where zero. All three may be scalars or arrays and broadcast together (each array operand materialized by `%la-broadcast-to`); width follows x, else y, else double. SELECTING is what keeps an infinite operand from becoming NaN |
| `take-rows a idx` | axis-0 slices by an index vector (`np.take(a, idx, axis=0)`); any rank >= 1 (whole-slab copies); indices truncate, may repeat; axis 0 is KEPT (one index -> a `(1 n)` matrix) |
| `row a i` | ONE axis-0 slice with axis 0 DROPPED (numpy `x[i]`). Rank >= 2 only (a vector errors -- `aref` already returns the element) |
| `gather a idx` | per-row elements `a[i, idx[i]]` of a matrix as a vector |
| `one-hot idx n &key element-type` | `(length idx) x n` matrix, row i = 1.0 at column `idx[i]` |
| `seed n` | reset the shared RNG deterministically; returns n |
| `rand shape &key element-type` / `randn` / `uniform lo hi shape` | uniform [0,1) / standard normal / uniform [lo,hi) from the seeded generator |
| `choice n size` / `permutation n` | size indices in [0,n) with replacement / Fisher-Yates shuffle of 0..n-1; both packed double vectors of integer values |

## Internal members that exist for `torch:` only

Nothing in the numpy surface reaches these; the `--simd` seam intercepts `linalg:` members and
nothing else, so a loop `torch.lisp` would otherwise spell inline as boxed `row-major-aref`
walks becomes a member here (`.kb/gpu.md`, `.kb/linalg-simd.md`).

- `%la-scatter-rows (z g idx)` (slab `i` of `g` ADDED into slab `idx[i]` of `z`, in place --
  `take-rows`' adjoint), `%la-sum-squares (g acc)` (a LEFT fold in double), `%la-scale (g s)`
  (in place), and `%la-adam-step`.
- The FUSED compositions: `%la-softmax-grad (g out ax)`, `%la-log-softmax-grad (g out ax)`,
  `%la-gelu (x)`, `%la-gelu-grad (g x old)`, `%la-layer-norm (x eps)`,
  `%la-layer-norm-grad (g x eps old)`, `%la-layer-norm-grad-norm (g x eps old)`,
  `%la-dropout-mask (shape p st single)`, `%la-scaled-masked-softmax (x scale mask fill ax)`,
  `%la-scaled-masked-softmax-grad (g out ax scale mask)`, `%la-layer-norm-affine (x w b eps)`,
  `%la-layer-norm-affine-grad (g x w eps old)`. These have NO kernel of their own on the CPU
  seams: each defun is the exact chain of `linalg:` members `torch.lisp` used to spell, member
  for member and in the tape's own order, so every CPU path produces the bits it always
  produced and only `--gpu` intercepts the member itself, as one pass (`.kb/gpu.md`,
  "The fused tier"; `.kb/torch.md`, "The fused compositions"). `%la-dropout-mask` advances its
  state vector `st` IN PLACE to the state the fill ends on and the caller restores the specials
  from it; the width rides as the `single` flag.
- Two members answer TWO arrays as a two-element LIST: `%la-layer-norm-affine-grad` answers
  `(dx gn)` (the input's gradient and `g * norm`, whose broadcast folds are the weight's
  gradient), and `%la-layer-norm-grad-norm` answers `dx` plus `norm` = `(linalg:div dev sd)`,
  read off the `dev`/`sd` that pass already holds instead of a second `%la-layer-norm` call. A
  LIST rather than a new call shape deliberately: an extra RESULT is not an extra argument, so
  the arity stays five and every seam carrying a five-argument member carries it unchanged (the
  interpreter's `define`, the JVM's `LinalgKernelCallLayout` BASE call shape, and the compiled
  cons `Object[]{dx, Object[]{gn, null}}`). `%la-layer-norm-grad` itself is untouched, since
  `torch::%m-layer-norm-forward`'s non-affine branch still calls it alone.

## Rank-N: the stacked matmul, the joins, and one odometer

All of it bottoms out in two internal walks, so there is exactly one place a strided read can be
wrong:

- `%la-gather-strided (a od rs base single)` -- fill a fresh `od`-shaped array by walking `a`'s
  flat row-major index from `base`, advancing by the INNERMOST-FIRST strides `rs` through the
  `%la-bcast-loop` odometer carry. `linalg:slice` builds `rs` from `step * axis-stride` and
  `base` from the start indices; `%la-broadcast-to` builds it from `%la-bcast-strides` (stride 0
  on a stretched axis) with `base` 0. The fifth parameter is the result WIDTH as a FLAG (`nil`
  double, non-nil single) because the walk is an intercepted `--simd` member on all three
  backends and a flag is what every kernel can read without a symbol comparison.
- `%la-matmul-nd` -- the batched product. `%la-batch-strides d od base` is `%la-bcast-strides`
  with a non-1 innermost stride (`base` = the trailing matrix's size), which is the whole
  difference between broadcasting an ELEMENT and broadcasting a MATRIX. The batch shape comes
  from `%la-batch-shape`, the M x K x N triple loop runs on flat indices, and the two batch
  offsets advance through the same odometer. The rank-2 `%la-matmul` stays the fast path.
- `%la-matmul-nd-ta` / `%la-matmul-nd-tb` -- the same product with one operand's last two axes
  exchanged (`a^T . b`, `a . b^T`), the shape both matmul adjoints have. Portably each is
  `%la-swap-last` plus the product it names, so nothing about the VALUE depends on which ran;
  they exist so an accelerator can read the operand in the orientation it is ALREADY stored in
  (`.kb/gpu.md`, "The transposed product"). Two arity-2 members rather than one with two flags,
  so every seam carrying `%la-matmul-nd` carries these unchanged. `torch:matmul`'s FORWARD
  reaches them too, over a `torch:transpose` view (`.kb/torch.md`).

Consequences: `linalg:dot` SIGNALS on rank >= 3 rather than reading a rank-3 operand's first two
dims as a matrix (silently wrong output before; the `--simd` `dot` kernel already declined
`rank > 2`, so all four backends agree on the message). `%la-matmul-nd` IS `--simd`-intercepted
-- rank <= 2 rides the `dot` kernel, the stacked path has its own running `dot`'s M.M lane loop
per batch offset, so its precision contract is a per-batch `linalg:dot`, not this defun; the
DISPATCH, the scalar rejection and both error messages stay in the library (the kernel declines a
rank-1 operand, mixed widths, a boxed operand, a non-broadcastable batch shape and a mismatched
inner dimension). No other rank-N addition is intercepted. `linalg:squeeze` returning the ELEMENT
when every axis goes is deliberate (no rank-0 array; `make-array` with a nil dims list is not a
shape the backends build).

## Why softmax and erf live here, and -inf through `where` -> `softmax`

`softmax`/`log-softmax` are scipy/torch, not numpy, and live here for the same reason `relu`
does: they are the array-level primitive an activation layer needs, one composition of kernels
the interceptors already know, and a second copy in the differentiable layer would fork the array
math. Both are max-subtracted, so a large logit cannot overflow. `erf` is here for one caller:
the EXACT GELU is `x * (1 + erf(x / sqrt(2))) / 2`, `nn.GELU`'s own default, and `torch.gelu`
wraps a kernel rather than reimplementing one (`.kb/torch.md`). Its accuracy depends on `exp`, so
on WASM it inherits that backend's software `exp`, like `tanh` and `softmax`.

Masked attention is `(linalg:where mask score -inf)` then `softmax`, and it works only because
`where` SELECTS: the older multiply-by-a-0.0/1.0-mask spelling turns `0.0 * -inf` into `NaN`.
All four backends now answer `0.0` for the infinity flowing through `amax` -> `sub` -> `exp` ->
`div`, so `-inf` is the right `masked-fill` value. It was not free:
`WasmExpCompiler`'s software `exp` is a degree-5 Taylor polynomial on `t = x / 256` followed by 8
squarings, and that polynomial has a real root near `t = -2.18` (`x = -558`) below which `p(t)`
is NEGATIVE and an even number of squarings makes it hugely POSITIVE -- `(exp -1000)` was
`2.4e125`, `(exp -inf)` was `+inf`, so a masked softmax returned `NaN` on WASM. A large finite
negative mask would NOT have been safer, for the same reason. Fix: one instruction,
`f64.max(p(t), 0.0)` before the squarings (`WasmExpCompiler.UNDERFLOW_CLAMP`, mirrored in
`WasmVecSimdRuntimeBuilder.emitExpF64` so `--simd` and `--no-gc` kernels stay bit-identical to
the defun). A no-op wherever `p(t) >= 0`, NaN still propagates (`f64.max` is NaN-propagating),
`+inf` untouched; `Math.exp` is `< 1e-217` over the whole clamped region, so the clamp is also
more accurate. Rest of the WASM transcendental contract: `.kb/vec.md`.

## Seeded RNG (the np.random analog)

Wichmann-Hill: three small multiplicative congruential generators
(`linalg::%la-rng-s1/-s2/-s3`, moduli 30269/30307/30323) combined into one uniform double, period
~6.95e12. Every intermediate stays below 2^23 (inside the WASM i31 range) and each draw is exact
integer arithmetic plus IEEE `+ - * /` on exact operands, so a SEEDED SEQUENCE IS BIT-IDENTICAL
ON ALL FOUR BACKENDS. `randn` is Irwin-Hall (sum of 12 uniforms minus 6), NOT Box-Muller: WASM's
`log`/`cos` are polynomial approximations that would break that identity while `+`/`-` cannot;
the tails clip at +-6 sigma (fine for weight init, not a distribution-exact `np.random.randn`).
`linalg:seed` discards ~10 draws so nearby seeds decorrelate. Deliberately no dependence on the
builtin `(random n)` (unseedable, backend-dependent).

`rand`/`randn`/`uniform` share ONE fill loop, `linalg::%la-rng-fill (out st mode lo span)`, which
takes the state as an ARRAY and answers the state it ends on as one -- that is what makes it a
pure function of its arguments and so interceptable. The three specials are its scratch
(`%la-rng-state` reads them into a vector, `%la-rng-restore` writes one back), so the generator's
rule lives in exactly one place, `%la-rng-next`, which the scalar draws (`%la-rng-int`, hence
`choice`/`permutation`, and `seed`'s discards) keep using directly. `mode` picks the element rule
(0 rand, 1 randn, 2 uniform).

Also internal: `linalg::%la-im2col x fh fw stride pad` ((N C H W) -> (N*oh*ow, C*fh*fw) window
unfold) and `linalg::%la-col2im col dims fh fw stride pad` (its scatter-add adjoint), backing the
deep-learning-from-scratch CNN examples (`examples/deep-learning-from-scratch/common/util.lisp`
wraps them as the book's `im2col`/`col2im`). numpy has no im2col, so they stay `%la-` internal;
both are direct index arithmetic (no pad copy / 6-D scratch / materialized transpose) and rank-4
only. Both ARE `--simd`-intercepted -- not with lanes (there is only index math) but as native
kernels, because the boxed element-at-a-time loop dominated everything once the matrix product
they feed was accelerated (~97% of a ch07 train run under `--simd`).

## Gotchas when writing programs

Results are fresh arrays (inputs are never mutated). Because linalg computes in double-float,
non-terminating results print at fewer significant digits on WASM than on the JVM, so
cross-backend-deterministic linalg output should stick to integer-valued or short-terminating
decimals (e.g. power-of-two matrices for `inv`/`solve`). Worked idioms:
`examples/ml/linear-regression.lisp`, `examples/ml/deep-digits.lisp`, `examples/ml/heat3d.lisp`.

## Single-float / width polymorphism

Double by default (precision) but accepts and preserves packed single-float (`#f`) so a `#f`
value flowing in from `vec:` is never silently widened -- the widening would force a mixed-width
`--simd` error on the next `vec:matvec`. Two orthogonal mechanisms:

- Constructor opt-in: every constructor takes `:element-type` (default `'double-float`) --
  `zeros`, `ones`, `full`, `eye`, `linspace`, `from-list`, `one-hot`, `rand`/`randn`/`uniform`,
  `arange`. `arange` is the one signature whose POSITIONAL count varies and CL's `&optional`
  greedily eats a following keyword, so it is `(&rest args)` split by
  `linalg::%la-split-element-type`: the `:element-type` pair may follow any positional count, any
  other keyword signals `Unknown keyword argument`, 0 or > 3 positionals signal.
- Width-following transforms: `add`/`sub`/`mul`/`div`/`emap` (via `%la-like`),
  `transpose`/`reshape`/`flatten`, `dot`/`matmul`/`outer`, `inv`/`solve` all preserve the (first)
  array input's width, so `(linalg:sub W grad)` keeps `W`'s width. Automatic, no API change.

The seam is `linalg::%la-make (dims init &optional element-type)`, whose two branches take a
LITERAL `:element-type`, so every backend -- interpreter, JVM and wasm-GC -- picks the
`double[]`/`float[]` (`TYPE_F64ARR`/`TYPE_F32ARR`) repr statically and produces `#f` directly; a
runtime-computed element-type could not. `linalg::%la-etype a` returns the literal symbol
matching `a`'s width (a general/boxed array reads back as `t`, mapping to `'double-float`) and
`%la-like` threads it. No `#+/#-rontolisp-wasm` reader conditional is needed -- unlike
`vec::%make-like`, whose double-only-on-wasm split predates the wasm-GC `TYPE_F32ARR` and is now
vestigial (`vec:` still renders a `#f` element-wise result as `#d` on wasm-GC while linalg
renders `#f` everywhere). Precision-critical `det`/`inv`/`solve` are best left double; a `#f`
input computes and returns in single-float, matching numpy. A cross-backend `#f` pin must use
f32-EXACT values (integers/halves) -- see the `linalg-single-float-cross-backend` ci-spec case.

## `--simd` acceleration

Twenty members are intercepted under `--simd` on the interpreter, the JVM and wasm-GC, reusing
the `vec:` lane loops: `add sub mul div sum norm amax amin argmax argmin trace transpose reshape
dot outer exp sqrt abs negative sign`. `mean`/`matmul`/`flatten`/`solve`/`square`/`reciprocal`
ride along through the `sum`/`dot`/`reshape`/`mul`/`div` their bodies call. `emap`, `det`, `inv`,
`array-equal`, `diff` and `gradient` are never intercepted.

linalg.lisp stays the ORACLE and is never rewritten to suit a kernel. Each kernel is a PARTIAL
function returning null for an input it does not handle (a general/boxed array, mixed widths, a
plain number, a ratio scalar, a shape mismatch); the call site then runs the scalar defun, which
supplies the exact behavior and error message. So `(linalg:add #(1 2) #(3 4))` and
`(linalg:add #d(1.0) #f(2.0))` keep working, just unaccelerated. Element-wise results are
bit-identical to the oracle at both widths; only reductions move (an `#f` reduction accumulates
in single precision). The matrix product is exempt and stays bit-identical. Full mechanics, the
precision contract and the `-0.0` cross-backend footgun: `.kb/linalg-simd.md`.

## Wiring

- Package: `linalg` is registered in the `PackageRegistry` constructor; exported names in
  `PackageRegistry.LINALG_FUNCTIONS` (`linalgFunctionNames()`). Like `rontolisp` it does not use
  `cl`, so inside `(in-package linalg)` standard names need `cl:`. Adding a function = the name
  there + a defun in `linalg.lisp` + per-operator doc pages (en and ja).
- Driver: `am.ik.rontolisp.eval.LinalgLibrary`, a simplified `JsonLibrary` mirror. NO call-site
  rewriting: every entry point is a plain defun (`&key`/`&optional`/`&rest` desugar through
  `LambdaLists`), so `process(program)` only detects usage (any `linalg:`/`linalg::` qualified
  symbol anywhere, or a bare exported name while `(in-package linalg)` is in effect) and prepends
  `forms()`.
- Interpreter (lazy load): no per-function dispatchers. `LispEvaluator.resolveFunction` falls
  back, on a missed lookup of a `linalg:`-qualified name, to evaluating `LinalgLibrary.forms()`
  into the global environment once and retrying. `#'linalg:name` uses the same path.
- Compile path: `RontoLispCli.compileToFile` and the playground
  (`RontoPlayground.compileJvm/Wasm`) wrap the program as
  `LinalgLibrary.process(JsonLibrary.process(...))`. Compiler unit tests must call
  `LinalgLibrary.process` explicitly (`compileAndRunLinalg` helpers).
- Native image: `linalg.lisp` is registered in
  `META-INF/native-image/.../resource-config.json` (typeReachable `LinalgLibrary`).

## Source constraints (linalg.lisp)

- Canonical package shape (external `linalg:name` defuns, internal `linalg::%la-*` helpers, bare
  `cl` names), so resolving it is a fixed-point no-op -- pinned by
  `PackageResolverTest.linalgLibraryFormsAreAResolverFixedPoint`.
- `%la-bcast`'s broadcast lambdas capture the operator and the scalar operand, so its parameters
  use `%la-` names (a harmless leftover from when the compiled backends resolved a captured name
  against a same-named user global first).
- The `linalg-package-cross-backend` ci-spec case uses power-of-two matrices for
  `det`/`inv`/`solve` so their float results print identically everywhere;
  `linalg-single-float-cross-backend` pins the `#f` path.
- Flat iteration uses `row-major-aref` / `(setf (row-major-aref ...))` directly, which is what
  makes the elementwise ops rank-generic.
- Not supported: `--no-gc` (arrays), runtime `eval` of linalg forms (the emitted eval runtime has
  no array ops).
- numpy general broadcasting: `%la-bcast` dispatches equal shapes to the flat loop and everything
  else to `%la-bcast-loop`, an odometer over stride lists (`%la-bcast-strides` pads a
  stretched/missing axis with stride 0; `%la-bcast-shape` aligns trailing axes -- extents equal
  or 1 -- and signals "linalg: shape mismatch" otherwise). The result keeps the FIRST array
  operand's width. The `--simd` kernels are untouched: a broadcast pair has unequal dims, which
  every kernel already declines.

## Standard array functions added alongside

`array-dimensions` and `row-major-aref`/`%row-major-aset` are backend primitives (interpreter
`Environment.registerArrays`; JVM helpers in `JvmArrayRuntimeBuilder`, where row-major access
reuses `_aref1`/`_aset1` because the data is stored flat right after the header; WASM inline
emission in `WasmArrayCompiler`). Everything else is `LispMacroExpander` expansion over existing
primitives: `vector` (make-array + %aset), `svref` (aref; also a setf place sharing the `%aset`
case), `array-rank`/`array-dimension`/`array-total-size`/`array-row-major-index` (over
array-dimensions), and `coerce` (literal `'list`/`'vector`/`'string` only, runtime dispatch on
listp/stringp). Trap: the JVM helper gating (`JvmLispCompiler.programUsesAnyArrayOp`) must list
the derived names too, because the scan runs before expansion. None are first-class function
values (matching `aref`/`make-array`).
