# The `linalg` package (numpy-style vector/matrix operations)

One hand-written Lisp-source library, `src/main/resources/am/ik/rontolisp/eval/linalg.lisp`,
following the `json.lisp` pattern (see `json.md`) so a single implementation
runs identically on all backends. 91 exported functions (constructors `zeros`/
`ones`/`full`/`eye`/`arange`/`linspace`/`from-list`, shape ops, broadcasting
`add`/`sub`/`mul`/`div`/`emap` + named ufuncs, products `dot`/`matmul`/`outer`,
reductions, calculus `diff`/`gradient`, and floating-point Gaussian-elimination
`det`/`inv`/`solve`) over the built-in arrays. The library computes in packed float (speed over exactness), **double by
default** but **width-polymorphic** (todo-097): a constructor opts into packed
single-float (`#f`) with an `:element-type` keyword, and every transform
PRESERVES its input width (see "Single-float / width polymorphism" below).
Elementwise ops, reductions, `reshape`/`flatten` and `array-equal`
walk elements via `row-major-aref`, so they work for any rank (`diff` too);
**`matmul` is rank-generic since todo-459** (rank >= 3 = the numpy stacked
product, below); `dot`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` stay
defined for rank <= 2, and `gradient` for vectors only.

**Options are numpy-style `&key` arguments, never trailing positionals** (the
2026-08-19 redesign; the library predates `&key` support): `:element-type` on every
constructor, `:axis` / `:keepdims` on the reductions, `:n` / `:axis` on `diff`. The
only positional options left are the ones numpy itself takes positionally:
`arange`'s start/stop/step, `transpose`'s axes list, `gradient`'s spacing. The
`--simd` interceptors pattern-match the LITERAL keywords at a call site
(`compiler.LinalgKernelCallLayout`, shared by both codegens; `LinalgSimd.options`
on the interpreter), so a spliced body that forwards an option (`mean` -> `sum`)
must spell the keyword literally.

## API quick reference (enough to write linalg programs)

Inputs are the ordinary built-in arrays: a vector is a rank-1 array `#(...)`,
a matrix rank-2 `#2A(...)` (higher ranks `#nA(...)`); both are readable literal
syntax as well as print syntax (the reader parses `#nA(...)` via
`Token.ArrayOpen`), so examples should prefer `#(1 2 3)` / `#2A((1 2) (3 4))`
over `from-list`. linalg RESULTS, however, are packed double-float arrays
(`LispDoubleFloatArray`) and print with the **`#d(...)`** reader syntax at every
rank (`#d(1.0 2.0 ...)` for a vector, nested `#d((...) ...)` for a matrix), so the
printed form round-trips to a packed array rather than degrading to a general one
-- an example that pins linalg output must expect `#d(...)`, not `#(...)` (the
todo-95 flip: `#d` is the double packed prefix, `#f` is now single-float).
Read/write elements with `aref` / `(setf (aref ...))`.
`shape` below means an integer `n` (vector) or a list of dimension sizes.
Stay in `cl-user` and call qualified names (the package does not use `cl`).
`#'linalg:name` works (they are plain defuns). Errors signal via `error`.

| Function | Semantics |
| --- | --- |
| `(linalg:zeros shape &key element-type)` / `(linalg:ones shape ...)` / `(linalg:full shape v ...)` | new array filled with 0 / 1 / v; `:element-type 'single-float` for `#f` (every constructor) |
| `(linalg:eye n)` | n x n identity matrix |
| `(linalg:arange stop)` / `(arange start stop)` / `(arange start stop step)` (+ `:element-type`) | vector, stop exclusive, step may be negative; `&rest`-parsed so the keyword may follow any positional count |
| `(linalg:linspace start stop n)` | n evenly spaced values inclusive (packed double-float) |
| `(linalg:from-list lst)` | flat list -> vector; list of equal-length rows -> matrix |
| `(linalg:to-list a)` | inverse of from-list |
| `(linalg:shape a)` / `(linalg:ndim a)` / `(linalg:size a)` | dims list `(n)` or `(rows cols)` / number of dimensions (0 for a plain number, numpy np.ndim) / total element count |
| `(linalg:reshape a shape)` | row-major copy; error if sizes differ; ONE extent may be `-1` and is inferred from the element count (numpy), a bare `-1` flattens |
| `(linalg:zeros-like a)` | fresh zero array with a's shape AND width |
| `(linalg:flatten a)` | rank-1 row-major copy |
| `(linalg:transpose a &optional axes)` | matrix transpose; a vector is returned unchanged. With an axes list (numpy `x.transpose(0 3 1 2)`): the rank-n axis permutation, out-dims[k] = dims[axes[k]] (each axis named exactly once); only the 1-arg matrix form is `--simd`-intercepted |
| `(linalg:pad a pads)` | constant-0 padding (numpy np.pad's default mode): pads = one `(before after)` pair per axis, or a single non-negative integer for both sides of every axis; keeps a's width |
| `(linalg:expand-dims a axis)` | a copy with an extent-1 axis inserted at `axis` (numpy np.expand_dims, torch unsqueeze); a negative axis counts from the end of the RESULT, so -1 appends. Row-major order is unchanged, so it is a `reshape` |
| `(linalg:squeeze a &key axis)` | extent-1 axes removed (numpy np.squeeze): all of them with no `:axis`, else only the integer (or list of) axes named -- a named axis whose extent is not 1 signals. Squeezing away EVERY axis returns the ELEMENT itself (linalg has no rank-0 arrays; a plain number is ndim 0) |
| `(linalg:concatenate arrays &key axis)` | the LIST `arrays` joined along an EXISTING axis (numpy np.concatenate / torch.cat; default 0, negative from the end). Equal ranks, equal extents off the axis; the axis extent is their sum. Width of the first input |
| `(linalg:stack arrays &key axis)` | the LIST `arrays` joined along a NEW axis (numpy np.stack): equal shapes, result rank + 1, the new axis of extent `(length arrays)` at `:axis` -- negative counts from the end of the RESULT, so -1 appends |
| `(linalg:slice a specs)` | numpy BASIC slicing, one spec per axis: `nil` = whole axis, or `(start end)` / `(start end step)`. Negative index = from the end, `nil` in the start/end position = from the beginning / to the end, negative step walks backwards, a MISSING trailing spec leaves that axis whole. Axes are KEPT (numpy `x[:, 0:3]`); dropping one is `row`. Runs on `%la-gather-strided` |
| `(linalg:triu a &key k)` / `(linalg:tril ...)` | the upper / lower triangle: a copy with everything below / above the k-th diagonal zeroed (numpy np.triu/np.tril, default k = 0). Rank >= 2, and a stack is masked on its LAST TWO axes. `(triu (ones '(n n)) :k 1)` is the causal / subsequent attention mask |
| `(linalg:add a b)` / `sub` / `mul` / `div` | elementwise with numpy broadcasting: a scalar operand on either side, and two arrays of different shapes along their trailing axes (extents equal or 1, missing leading axis = 1; anything else = the shape-mismatch error); result keeps the first array operand's width; `mul` is Hadamard (NOT matrix product) |
| `(linalg:+ &rest a)` / `(linalg:- a &rest r)` / `(linalg:* &rest a)` / `(linalg:/ a &rest r)` | the CL operator spellings: n-ary LEFT FOLDS of add/sub/mul/div (plain defuns, so `#'linalg:+` works). Degenerate arities follow CL: no argument is 0 / 1, one argument to `+`/`*` is itself, one argument to `-`/`/` is the negation / reciprocal (via `(sub 0 a)` / `(div 1 a)`, so a scalar operand works too). Each fold step is a LITERAL `linalg:add`/... call, so `--simd` still intercepts the kernel inside the alias; only the `&rest` list is extra |
| `(linalg:emap f a)` | fresh array with f applied to every element |
| `(linalg:exp a)` / `sqrt` / `abs` / `square` / `negative` / `sign` / `reciprocal` | named elementwise unary ufuncs (numpy parity, todo 109): `emap` of the obvious scalar op (`square` = `mul a a`, `reciprocal` = `div 1 a`); unlike `emap` they are `--simd`-interceptable |
| `(linalg:power a b)` | elementwise `a ** b` (numpy np.power), through `%la-bcast` like `mul` -- either operand may be a scalar, two arrays broadcast. Not `--simd`-intercepted (no `expt` kernel) |
| `(linalg:softmax a &key axis)` / `(linalg:log-softmax ...)` | the max-subtracted softmax and its log: no `:axis` = the whole array is one distribution (scipy's default), an integer `:axis` = one distribution per slice (torch's `softmax(x, dim)`). `log-softmax` is `(x - m) - log(sum(exp(x - m)))`, NOT `(log (softmax x))`, so a zero weight gives -inf and not NaN. Not in numpy proper -- see "Why softmax lives here" below |
| `(linalg:erf a)` | elementwise Gauss error function (`scipy.special.erf`, not numpy) -- see "Why softmax lives here" below. Accurate to a double's last ulps over the WHOLE range: the all-positive-term series A&S 7.1.6 (`%la-erf-1`), NOT the alternating Maclaurin series, whose cancellation loses every digit by \|x\| ~ 3; exactly +-1 beyond \|x\| = 6, which also bounds the loop. No `erfc` member: `(sub 1.0 (erf a))` is it, and the far tail where a real `erfc` would win is where `erf` is already 1 |
| `(linalg:dot a b)` | numpy dispatch: vec.vec -> scalar, mat.vec / vec.mat -> vector, mat.mat -> matrix product; scalar operand multiplies elementwise. Rank >= 3 on either side SIGNALS (`linalg: dot expects rank <= 2 ...`) -- numpy's np.dot contracts against the other operand's second-to-last axis there, which is not the stacked product, and the old code silently read a rank-3 operand as a matrix |
| `(linalg:matmul a b)` | matrix product (also mat.vec) at rank <= 2, via `dot`; at rank >= 3 on EITHER side the numpy STACKED product (`%la-matmul-nd`, = torch.bmm / torch.matmul): the last two axes are the matrix, every leading axis broadcasts, and a rank-1 operand is promoted (row on the left, column on the right) with its axis dropped again. Rejects scalar operands at every rank |
| `(linalg:outer u v)` | outer product (inputs flattened first) |
| `(linalg:sum a &key axis keepdims)` / `(linalg:mean ...)` | no axis: over all elements (a reduction follows the element type: a packed/float array reduces to a double, a plain integer array to an integer/ratio; non-nil keepdims wraps the scalar in an all-ones-shape array). Integer axis (negative counts from the end): reduce along that axis, axis dropped -- kept as extent 1 under keepdims; a vector without keepdims reduces to the scalar itself. Any rank (row-major outer x axis x inner fold) |
| `(linalg:var a &key axis keepdims ddof)` / `(linalg:std ...)` | the variance / standard deviation, whole-array or along an axis (the `sum` rules), divided by `n - ddof`: `:ddof 0` (the default) is numpy np.var and torch's `unbiased=False`, `:ddof 1` the sample variance. `std` is `sqrt` of `var` -- `cl:sqrt` on the scalar result, `linalg:sqrt` on the array one. `mean` + `std` along one axis is LayerNorm |
| `(linalg:amax a &key axis keepdims)` / `(linalg:amin ...)` | largest / smallest element, whole-array or along an axis (same rules as sum); strict-comparison fold (first wins ties, NaN never replaces the seed); error on an empty array or axis |
| `(linalg:argmax v &key axis)` / `(linalg:argmin ...)` | no axis: index in a VECTOR (first on ties). With an axis: per-slice indices, axis dropped; rank >= 2 results are a packed DOUBLE array of index values (no integer arrays in linalg; `(= 3.0 3)` holds), a vector reduces to the integer index |
| `(linalg:norm a)` | Euclidean / Frobenius norm (a float, via sqrt) |
| `(linalg:trace a)` | main-diagonal sum; square matrices only |
| `(linalg:diff a &key n axis)` | n-th discrete difference along `:axis` (numpy np.diff; default n = 1, default axis -1 = last, negative counts from the end; each step shortens that axis by one, clamped at 0); any rank (outer x axis x inner walk); n = 0 returns a packed copy |
| `(linalg:gradient f)` / `(gradient f spacing)` | numerical derivative of a VECTOR of samples (numpy np.gradient): second-order central differences inside, first-order one-sided at the ends, same length as f; spacing = a uniform scalar (default 1) or a same-length coordinate vector (non-uniform, numpy's exact interior formula); needs >= 2 samples |
| `(linalg:det a)` / `(linalg:inv a)` / `(linalg:solve a b)` | Gaussian elimination with partial pivoting in floating point (double); a singular matrix's `det` may be a small epsilon rather than exactly 0; `inv` errors on a singular matrix; `solve` solves a.x = b for a vector or matrix b |
| `(linalg:array-equal a b)` | same shape + numerically equal elements (1 = 1.0); needed because arrays themselves are `eq`-compared only |
| `(linalg:equal a b)` / `greater` / `greater-equal` / `less` / `less-equal` | elementwise comparison as a 0.0/1.0 MASK of the first array operand's width (numpy `==`/`>`/`>=`/`<`/`<=`); scalars and broadcasting via `%la-bcast`; multiply by the mask where numpy would boolean-index (relu grad, dropout) |
| `(linalg:where mask x y)` | elementwise SELECT (numpy np.where): x's element where mask is non-zero, y's where it is zero, so the 0.0/1.0 masks above drive it directly. All three may be scalars or arrays and broadcast together (each array operand is materialized at the broadcast shape by `%la-broadcast-to`); the width follows x, else y, else double. Selecting rather than multiplying is what keeps an infinite operand from becoming NaN -- see "-inf through where -> softmax" below |
| `(linalg:take-rows a idx)` | axis-0 slices selected by an index vector (numpy `x[mask]` / `np.take(a, idx, axis=0)`); ANY rank >= 1 (whole-slab copies, so rank-4 batches work); indices truncate, may repeat; axis 0 is KEPT (one index -> a `(1 n)` matrix, numpy `x[[i]]`) |
| `(linalg:row a i)` | ONE axis-0 slice with axis 0 DROPPED (numpy `x[i]`): a matrix -> the row vector, a rank-4 batch -> the rank-3 sample. Rank >= 2 only (a vector errors -- `aref` already returns the element). The reason a per-sample forward pass reads `(linalg:row x i)` and not `(linalg:flatten (linalg:take-rows x (linalg:from-list (list i))))` |
| `(linalg:gather a idx)` | per-row elements `a[i, idx[i]]` of a matrix (numpy `y[np.arange(n), t]`) as a vector |
| `(linalg:one-hot idx n &key element-type)` | `(length idx) x n` matrix, row i = 1.0 at column `idx[i]` |
| `(linalg:seed n)` | reset the shared RNG deterministically; returns n |
| `(linalg:rand shape &key element-type)` / `(linalg:randn ...)` / `(linalg:uniform lo hi shape ...)` | uniform [0,1) / standard normal / uniform [lo,hi) arrays from the seeded generator |
| `(linalg:choice n size)` / `(linalg:permutation n)` | size indices in [0,n) with replacement (np.random.choice default) / Fisher-Yates shuffle of 0..n-1; both packed double vectors of integer values |

## Rank-N (todo-459): the stacked matmul, the joins, and one odometer

Everything the rank-N round added is numpy parity, and all of it bottoms out in two
internal walks so there is exactly one place where a strided read can be wrong:

- **`%la-gather-strided (a od rs base etype)`** -- fill a fresh `od`-shaped array by
  walking `a`'s flat row-major index from `base`, advancing by the INNERMOST-FIRST
  strides `rs` through the `%la-bcast-loop` odometer carry. `linalg:slice` builds
  `rs` from `step * axis-stride` and `base` from the start indices;
  `%la-broadcast-to` builds it from `%la-bcast-strides` (stride 0 on a stretched
  axis) with `base` 0. Nothing else needs a per-element division.
- **`%la-matmul-nd`** -- the batched product. `%la-batch-strides d od base` is
  `%la-bcast-strides` with a non-1 innermost stride (`base` = the trailing matrix's
  size), which is the whole difference between "broadcast an element" and "broadcast
  a MATRIX". The batch shape comes from `%la-batch-shape` (the `%la-bcast-shape` rule
  with matmul's own message), the M x K x N triple loop runs on flat indices, and the
  two batch offsets advance through the same odometer. The rank-2 `%la-matmul` stays
  the fast path -- `linalg:matmul` only reaches `%la-matmul-nd` when a side has rank
  >= 3.

Consequences worth knowing:

- **`linalg:dot` now SIGNALS on rank >= 3** instead of reading a rank-3 operand's
  first two dims as a matrix. That was silently wrong output, not an error, and the
  `--simd` `dot` kernel already declined `rank > 2` (`LinalgSimd.dot`), so the
  interpreter, JVM and both WASM backends agree on the new message.
- **Neither `%la-matmul-nd` nor any other rank-N addition is `--simd`-intercepted.**
  The rank <= 2 path still rides the `dot` kernel; the stacked path runs the scalar
  defun. Batched matmul is the kernel a transformer forward pass spends its time in,
  so it is the first candidate to measure for its own interceptor
  (`.kb/linalg-simd.md`) -- measure before adding one.
- `linalg:squeeze` returning the ELEMENT when every axis goes is deliberate: linalg
  has no rank-0 array, `%la-fold-axis` already reduces a vector to the scalar itself,
  and `make-array` with a nil dims list is not a shape the backends build.

## Why softmax lives here, and -inf through `where` -> `softmax`

`softmax` / `log-softmax` are not numpy (they are `scipy.special.softmax` /
`torch.softmax`), and they are in `linalg` for the same reason `relu` is: they are the
array-level primitive an activation layer needs, they are one composition of kernels
the interceptors already know, and a second copy in the differentiable layer above
would fork the array math. Both are max-subtracted, so a large logit cannot overflow.

`erf` is here on the same rule and for one caller: the EXACT GELU is
`x * (1 + erf(x / sqrt(2))) / 2`, which is `nn.GELU`'s own default, and `torch.gelu`
wraps a kernel rather than reimplementing one (`.kb/torch.md`'s standing rule). The
alternative was to ship only the `tanh` approximation and call the difference a
documented divergence -- rejected, because the "why" would have been "we did not add
erf", which is not a reason that can ever stop holding. What its accuracy DOES depend
on is `exp`, so on the WASM backends it inherits that backend's software `exp`, the
same way `tanh` and `softmax` already do.

The masked-attention idiom is `(linalg:where mask score -inf)` then `softmax`, and it
only works because `where` SELECTS: the older "multiply by a 0.0/1.0 mask" spelling
turns `0.0 * -inf` into `NaN`. What the four backends do with the infinity that then
flows through `amax` -> `sub` -> `exp` -> `div` (the question todo-459 was asked to
settle before todo-460 commits `masked-fill` to `-inf` rather than a large finite
negative):

- The answer is now **`0.0` on all four**, and `-inf` is the right choice for
  `masked-fill`. A large finite negative would NOT have been safer -- see why.
- It was not free. `WasmExpCompiler`'s software `exp` is a degree-5 Taylor polynomial
  on `t = x / 256` followed by 8 squarings, and that polynomial has a real root near
  `t = -2.18` (`x = -558`): below it `p(t)` is NEGATIVE, and an even number of
  squarings turned it into a huge POSITIVE number. `(exp -1000)` was `2.4e125` and
  `(exp -inf)` was `+inf` on both WASM backends, so a masked softmax returned `NaN`
  there while the interpreter and JVM returned `0.0`. That is also why a "large finite
  negative" mask would not have rescued it: `exp` of it was equally wrong.
- The fix is one instruction, `f64.max(p(t), 0.0)` before the squarings
  (`WasmExpCompiler.UNDERFLOW_CLAMP`, mirrored in
  `WasmVecSimdRuntimeBuilder.emitExpF64` so the `--simd` and `--no-gc` kernels stay
  bit-identical to the defun). It is a NO-OP wherever `p(t) >= 0`, so every value the
  approximation already got right is unchanged bit-for-bit; NaN still propagates
  (`f64.max` is NaN-propagating) and `+inf` is untouched. `Math.exp` returns
  `< 1e-217` over the whole clamped region, so the clamp is also the more accurate
  answer. See `.kb/vec.md` for the rest of the WASM transcendental contract.

## Seeded RNG (the np.random analog; deep-learning-from-scratch port)

The generator is **Wichmann-Hill**: three small multiplicative congruential
generators (`defparameter linalg::%la-rng-s1/-s2/-s3`, moduli 30269/30307/30323)
combined into one uniform double, period ~6.95e12. Every intermediate stays
below 2^23 (inside the WASM i31 range) and each draw is exact integer
arithmetic plus IEEE `+ - * /` on exact operands, so **a seeded sequence is
bit-identical on all four backends** -- weight init and mini-batch sampling in
the examples reproduce exactly everywhere. `randn` is **Irwin-Hall** (sum of 12
uniforms minus 6), NOT Box-Muller: WASM's `log`/`cos` are polynomial
approximations that would break the cross-backend identity, while `+`/`-`
cannot; the tails clip at +/- 6 sigma (fine for weight init, not a
distribution-exact `np.random.randn`). `linalg:seed` discards ~10 draws after
setting the state so nearby seeds decorrelate. There is deliberately no
dependence on the builtin `(random n)` (unseedable, backend-dependent).

Internal (non-exported, todo-117): `linalg::%la-im2col x fh fw stride pad`
((N C H W) -> (N*oh*ow, C*fh*fw) window unfold) and `linalg::%la-col2im col
dims fh fw stride pad` (its scatter-add adjoint) back the
deep-learning-from-scratch CNN examples (`examples/deep-learning-from-scratch/
common/util.lisp` wraps them as the book's `im2col`/`col2im`). numpy has no
im2col either, so they stay `%la-` internal; both are direct index arithmetic
(no pad copy / 6-D scratch / transpose materialized) and rank-4 only. Both ARE
`--simd`-intercepted (todo-117 follow-up `8987590`) -- not with lanes (there is
no arithmetic to vectorize, only index math) but as native kernels, because the
boxed element-at-a-time loop dominated everything else once the matrix product
they feed was accelerated (~97% of a ch07 train run under `--simd` was
im2col/col2im).

Gotchas when writing programs:
`dot`/`matmul`/`outer`/`det`/`inv`/`solve`/`trace` and 1-arg `transpose` are
rank <= 2 only (the axes form of `transpose` and everything else is
rank-generic); results are fresh arrays
(inputs are never mutated);
because linalg now computes in double-float, non-terminating results print at
fewer significant digits on WASM than on the JVM, so cross-backend-deterministic
linalg output should stick to integer-valued or short-terminating-decimal
results (e.g. power-of-two matrices for `inv`/`solve`, whose inverses are exact
short decimals). See `examples/ml/linear-regression.lisp`,
`examples/ml/deep-digits.lisp` and `examples/ml/heat3d.lisp` for worked idioms
(incl. an i31-safe fixed-seed LCG, matrix backpropagation and the rank-3 idioms).

## Single-float / width polymorphism (todo-097)

linalg is **double by default** (precision) but accepts and preserves packed
single-float (`#f`) so a `#f` value flowing in from `vec:` is never silently
widened back to double -- the widening would force a mixed-width `--simd` error on
the next `vec:matvec` (a `#d` matrix x a `#f` vector). Two orthogonal mechanisms:

- **Constructor opt-in.** Every constructor takes an `:element-type` keyword
  (default `'double-float`): `(linalg:zeros '(3 4) :element-type 'single-float)`,
  `(linalg:ones n :element-type 'single-float)`, `(linalg:full shape v :element-type
  'single-float)`, `(linalg:eye n :element-type 'single-float)`, `(linalg:linspace a b n
  :element-type 'single-float)`, `(linalg:from-list lst :element-type 'single-float)`,
  `(linalg:one-hot idx n ...)`, `(linalg:rand shape ...)` / `randn` / `uniform`, and
  `(linalg:arange ... :element-type 'single-float)`. `arange` is the one signature whose
  POSITIONAL count varies (`stop` / `start stop` / `start stop step`), and CL's
  `&optional` greedily eats a following keyword, so it is `(&rest args)` split by
  `linalg::%la-split-element-type`: the `:element-type` pair may follow any positional
  count, any other keyword signals `Unknown keyword argument`, 0 or > 3 positionals
  signal. (Before 2026-08-19 the whole family took a trailing positional symbol, a
  relic of the pre-`&key` era; the interceptors and `--no-gc` read the keyword now.)
- **Width-following transforms.** `add`/`sub`/`mul`/`div`/`emap` (via
  `%la-like`), `transpose`/`reshape`/`flatten`, `dot`/`matmul`/`outer` and
  `inv`/`solve` all PRESERVE the (first) array input's width -- a `#f` stays `#f`,
  a `#d` stays `#d` -- so a functional weight update `(linalg:sub W grad)` keeps
  `W`'s single-float width. No API change; it is automatic.

The seam is `linalg::%la-make (dims init &optional element-type)`: `(if (eq
element-type 'single-float) (make-array ... 'single-float ...) (make-array ...
'double-float ...))`. Both branches take a **literal** `:element-type`, so every
backend -- interpreter, JVM AND **wasm-GC** -- picks the `double[]`/`float[]`
(`TYPE_F64ARR`/`TYPE_F32ARR`) repr statically and produces `#f` directly; a
runtime-computed element-type could not. `linalg::%la-etype a` returns the literal
symbol matching `a`'s width (a general/boxed array reads back as `t`, so it maps to
`'double-float`), and `%la-like` threads it. **No `#+/#-rontolisp-wasm` reader
conditional is needed** -- unlike `vec::%make-like`, whose split (double-only on
wasm) predates the wasm-GC `TYPE_F32ARR` (todo-095 Phase 4) and is now vestigial:
`vec:` still renders a `#f` element-wise result as `#d` on wasm-GC while linalg
renders `#f` on every backend. Because single-float trades precision for speed,
precision-critical `det`/`inv`/`solve` are best left double (the default); a `#f`
input to `inv`/`solve` computes (and returns) in single-float, matching numpy.

Cross-backend determinism: like double results, a non-terminating `#f` decimal
prints differently on WASM, so a cross-backend `#f` pin must use f32-EXACT values
(integers / halves) -- see the `linalg-single-float-cross-backend` ci-spec case,
which is byte-identical on all four backends.

## `--simd` acceleration (todo-107)

Twenty members -- `add` `sub` `mul` `div` `sum` `norm` `amax` `amin` `argmax` `argmin`
`trace` `transpose` `reshape` `dot` `outer` plus the unary ufuncs `exp` `sqrt` `abs`
`negative` `sign` (todo 109) -- are intercepted under `--simd` on the interpreter, the
JVM and wasm-GC, reusing the `vec:` lane loops. `mean`/`matmul`/`flatten`/`solve`/
`square`/`reciprocal` ride along through the `sum`/`dot`/`reshape`/`mul`/`div` their
bodies call. `emap`, `det`, `inv`, `array-equal`, `diff` and `gradient` are never
intercepted.

**linalg.lisp stays the oracle and is never rewritten to suit a kernel.** Each kernel is a
PARTIAL function: it returns null for an input it does not handle -- a general (boxed)
array, mixed widths, a plain number, a ratio scalar, a shape mismatch -- and the call site
then runs the scalar defun, which supplies the exact behavior and the exact error message.
So `(linalg:add #(1 2) #(3 4))` and `(linalg:add #d(1.0) #f(2.0))` keep working unchanged
under `--simd`, just unaccelerated.

Element-wise results are bit-identical to the oracle at both widths; only the reductions
move (an `#f` reduction accumulates in single precision, the todo-106 contract). The matrix
product is exempt and stays bit-identical. Full mechanics, the precision contract, the
benchmarks and the `-0.0` cross-backend footgun: **`.kb/linalg-simd.md`**.

## Wiring

- **Package**: `linalg` is a built-in package registered in the
  `PackageRegistry` constructor; the exported names live in
  `PackageRegistry.LINALG_FUNCTIONS` (exposed as `linalgFunctionNames()`).
  Like `rontolisp`, it does not use `cl`, so inside `(in-package linalg)`
  standard names need `cl:` qualification. Adding a function = add the name
  there + a defun in `linalg.lisp` (+ per-operator doc pages, en and ja).
- **Driver**: `am.ik.rontolisp.eval.LinalgLibrary`, a simplified `JsonLibrary`
  mirror. Unlike JSON there is **no call-site rewriting**: every entry point is
  a plain defun (`&key`/`&optional`/`&rest` desugar through `LambdaLists`), so
  `process(program)` only detects usage (any `linalg:`/`linalg::` qualified
  symbol anywhere, or a bare exported name while `(in-package linalg)` is in
  effect) and prepends `forms()`.
- **Interpreter (lazy load)**: no per-function dispatchers. `LispEvaluator.
  resolveFunction` falls back, on a missed lookup of a `linalg:`-qualified
  name, to evaluating `LinalgLibrary.forms()` into the global environment once
  and retrying. `#'linalg:name` works through the same path.
- **Compile path**: `RontoLispCli.compileToFile` and the web playground
  (`RontoPlayground.compileJvm/Wasm`) wrap the program as
  `LinalgLibrary.process(JsonLibrary.process(...))`. Compiler unit tests must
  call `LinalgLibrary.process` explicitly (see `compileAndRunLinalg` helpers).
- **Native image**: `linalg.lisp` is registered in
  `META-INF/native-image/.../resource-config.json` (typeReachable
  `LinalgLibrary`).

## Source constraints (linalg.lisp)

- Written in canonical package shape (external `linalg:name` defuns, internal
  `linalg::%la-*` helpers, bare `cl` names), so resolving it is a fixed-point
  no-op — pinned by `PackageResolverTest.linalgLibraryFormsAreAResolverFixedPoint`.
- `%la-bcast`'s broadcast lambdas capture the operator and the scalar operand,
  so its parameters use `%la-` names, a leftover workaround from when the
  compiled backends resolved a captured name against a same-named user global
  first (fixed 2026-07-03 in `Jvm/WasmLambdaCompiler`; the ci-spec
  `dynamic-function-selection` case defines a global `f` and broke `linalg:add`
  before the rename — the rename stays because it is harmless).
- Arithmetic runs in packed float (speed over exactness), double by default but
  width-polymorphic (see "Single-float / width polymorphism"); the
  `linalg-package-cross-backend` ci-spec case uses power-of-two matrices for
  `det`/`inv`/`solve` so their float results are exact and print identically on
  every backend, and `linalg-single-float-cross-backend` pins the `#f` path.
- Flat iteration uses `row-major-aref` / `(setf (row-major-aref ...))`
  directly (the former `%la-cols`/`%la-fref`/`%la-fset` cols-encoding helpers
  were deleted when rank-n arrays landed), which is what makes the elementwise
  ops rank-generic.
- Not supported: `--no-gc` (arrays), runtime `eval` of linalg forms (the
  emitted eval runtime has no array ops).
- numpy general broadcasting between arrays of different shapes landed
  2026-07-12: `%la-bcast` dispatches equal shapes to the (unchanged) flat loop
  and everything else to `%la-bcast-loop`, an odometer walk over stride lists
  (`%la-bcast-strides` pads a stretched/missing axis with stride 0;
  `%la-bcast-shape` aligns trailing axes -- extents equal or 1 -- and signals
  the same "linalg: shape mismatch" otherwise). The result keeps the FIRST
  array operand's width, like the mixed-width rule. The --simd kernels are
  untouched: a broadcast pair has unequal dims, which every kernel already
  declines, so it falls back to the defun (see `.kb/linalg-simd.md`).

## Standard array functions added alongside

`array-dimensions` and `row-major-aref`/`%row-major-aset` are the backend
primitives (interpreter `Environment.registerArrays`; JVM helpers in
`JvmArrayRuntimeBuilder` -- row-major access reuses `_aref1`/`_aset1` because
the data is stored flat right after the header; WASM inline emission in
`WasmArrayCompiler`). Everything else is `LispMacroExpander` expansion over
existing primitives: `vector` (make-array + %aset), `svref` (aref; also a setf
place sharing the `%aset` case), `array-rank`/`array-dimension`/
`array-total-size`/`array-row-major-index` (over array-dimensions), and
`coerce` (literal `'list`/`'vector`/`'string` only, runtime dispatch on
listp/stringp). The JVM helper gating
(`JvmLispCompiler.programUsesAnyArrayOp`) must list the derived names too,
because the scan runs before expansion. None are first-class function values
(matching `aref`/`make-array`).
