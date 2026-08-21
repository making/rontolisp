# Vectors & Matrices (linalg)

The `linalg` package provides a numpy-style API for vectors and matrices: constructors, shape manipulation, elementwise arithmetic, products, reductions, discrete calculus (differences and numerical derivatives), and linear algebra (determinant, inverse, linear solving).

Like the JSON library, `linalg` is implemented once in Lisp source (`linalg.lisp`): the interpreter loads the definitions lazily on the first use of a `linalg:` function, and the compile path splices them into the program when it references the package. There is no per-backend code, so every function behaves identically on the interpreter, the JVM compiler, WASM Preview 1 and the WASM component.

## Data representation

linalg constructors build [packed float arrays](../reference/data-types.md): unboxed `(array double-float)` values, the same representation as an `#d(...)` literal. A vector is a rank-1 array, printed `#d(1.0 2.0 ...)`, and a matrix is a rank-2 array, printed with the nested `#d((...) ...)` form -- the `#d` marks the unboxed packed representation, so the printed form reads back as a packed array. Individual elements are read and written with `aref`, and any array built elsewhere -- packed or a general boxed array -- can be handed to a linalg function. Arrays of higher rank work too: the elementwise operations, the reductions, `reshape`/`flatten` and `array-equal` walk the elements in flat row-major order and accept any rank, while `matmul` stacks rank >= 3 on its last two axes (numpy's own `np.matmul` rule) and `dot`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` stay defined for vectors and matrices (rank <= 2), like numpy's specialized routines. [`linalg:from-list`](../reference/functions/linalg-from-list.md) / [`linalg:to-list`](../reference/functions/linalg-to-list.md) convert between arrays and lists.

linalg computes in floating point, prioritizing speed: every constructor and array-building operation returns a packed double-float array by default (single-float is available too -- see [Single-float precision](#single-float-precision)), and [`linalg:det`](../reference/functions/linalg-det.md), [`linalg:inv`](../reference/functions/linalg-inv.md) and [`linalg:solve`](../reference/functions/linalg-solve.md) run in floating point (like numpy), so a general inverse carries the usual rounding and a nearly singular determinant can be a small epsilon rather than exactly `0`. A reduction follows the element type numpy-style: a reduction over a packed or float array is a double, while a reduction over a plain integer array (a bare `#(1 2 3)` literal) stays an integer or exact ratio; [`linalg:norm`](../reference/functions/linalg-norm.md) is always a float because `sqrt` is. One cross-backend caveat: the WASM backends print a non-terminating float at fewer significant digits than the interpreter and JVM, so a rounded inverse or an irrational norm can look different across backends even though the underlying `double` is identical.

## A worked example

```lisp
(linalg:eye 3)                          ; => #d((1.0 0.0 0.0) (0.0 1.0 0.0) (0.0 0.0 1.0))
(linalg:arange 5)                       ; => #d(0.0 1.0 2.0 3.0 4.0)
(linalg:linspace 0 1 5)                 ; => #d(0.0 0.25 0.5 0.75 1.0)
(linalg:matmul #2A((1 2) (3 4))
               #2A((5 6) (7 8)))        ; => #d((19.0 22.0) (43.0 50.0))
(linalg:det #2A((1 2) (3 4)))           ; => -2.0
(linalg:inv #2A((4 0) (2 4)))           ; => #d((0.25 0.0) (-0.125 0.25))
(linalg:solve #2A((4 0) (2 4)) #(8 8))  ; => #d(2.0 1.0)
```

The `inv` and `solve` matrices above are chosen so their float results are exact and print identically on every backend; a general inverse such as `(linalg:inv #2A((1 2) (3 4)))` computes the same values but carries floating-point rounding.

`la` is a built-in nickname for `linalg`, so every `linalg:` call can also be written with the shorter `la:` prefix:

```lisp
(la:arange 5) ; => #d(0.0 1.0 2.0 3.0 4.0)
```

## Elementwise arithmetic and broadcasting

[`linalg:add`](../reference/functions/linalg-add.md), [`linalg:sub`](../reference/functions/linalg-sub.md), [`linalg:mul`](../reference/functions/linalg-mul.md) and [`linalg:div`](../reference/functions/linalg-div.md) operate elementwise and broadcast by numpy's rules: a scalar operand on either side is broadcast over the other operand's shape, and two arrays of different shapes align their trailing axes -- each aligned pair of extents must be equal or contain a 1 (a missing leading axis counts as 1), and the axis of extent 1 is stretched over the other operand's extent. A pair that fits neither rule signals a shape-mismatch error. The result keeps the first array operand's element type, matching the mixed-width rule. Note that `mul` is the Hadamard (elementwise) product -- the matrix product is [`linalg:matmul`](../reference/functions/linalg-matmul.md) (or the rank-dispatching [`linalg:dot`](../reference/functions/linalg-dot.md)). Arbitrary per-element transformations go through [`linalg:emap`](../reference/functions/linalg-emap.md).

The four also answer to their CL operator spellings -- [`linalg:+`](../reference/functions/linalg-plus.md), [`linalg:-`](../reference/functions/linalg-minus.md), [`linalg:*`](../reference/functions/linalg-star.md) and [`linalg:/`](../reference/functions/linalg-slash.md) -- which are n-ary left folds of `add` / `sub` / `mul` / `div`, so `(linalg:+ a b c)` broadcasts step by step and each step is still the accelerated kernel. The degenerate arities follow CL: no argument gives the identity (`0` / `1`), a single argument to `+` and `*` is itself, and a single argument to `-` and `/` is the negation / reciprocal. (The [`vec:`](simd-acceleration.md) package's operator aliases are strictly binary instead -- its kernels are fixed-arity by design.)

The frequent per-element operations also exist under their numpy ufunc names: [`linalg:exp`](../reference/functions/linalg-exp.md), [`linalg:log`](../reference/functions/linalg-log.md), [`linalg:tanh`](../reference/functions/linalg-tanh.md), [`linalg:sin`](../reference/functions/linalg-sin.md), [`linalg:cos`](../reference/functions/linalg-cos.md), [`linalg:tan`](../reference/functions/linalg-tan.md), [`linalg:asin`](../reference/functions/linalg-asin.md), [`linalg:acos`](../reference/functions/linalg-acos.md), [`linalg:atan`](../reference/functions/linalg-atan.md), [`linalg:sinh`](../reference/functions/linalg-sinh.md), [`linalg:cosh`](../reference/functions/linalg-cosh.md), [`linalg:sqrt`](../reference/functions/linalg-sqrt.md), [`linalg:abs`](../reference/functions/linalg-abs.md), [`linalg:square`](../reference/functions/linalg-square.md), [`linalg:negative`](../reference/functions/linalg-negative.md), [`linalg:sign`](../reference/functions/linalg-sign.md) and [`linalg:reciprocal`](../reference/functions/linalg-reciprocal.md), the binary [`linalg:power`](../reference/functions/linalg-power.md), plus the comparison selects [`linalg:maximum`](../reference/functions/linalg-maximum.md), [`linalg:minimum`](../reference/functions/linalg-minimum.md), [`linalg:clip`](../reference/functions/linalg-clip.md) and [`linalg:relu`](../reference/functions/linalg-relu.md) (defined by the strict comparison `(if (> x y) x y)` and its mirrors, so the second operand or the bound wins any false comparison -- ties and `NaN` included, identically on every backend). Each is equivalent to the obvious `emap` (or `mul` / `div` / `maximum` / `minimum` call), but as named functions they are accelerated under [`--simd`](simd-acceleration.md#accelerating-linalg), which `emap` with an arbitrary callback never is. [`linalg:softmax`](../reference/functions/linalg-softmax.md), [`linalg:log-softmax`](../reference/functions/linalg-log-softmax.md) and [`linalg:erf`](../reference/functions/linalg-erf.md) sit here for the same reason `relu` does -- they are not in numpy proper (they are scipy's / torch's), but they are the array-level primitive an activation layer needs. The two softmaxes are the max-subtracted, numerically stable forms; `erf` is what the exact GELU is built from, and it sums the all-positive-term series rather than the alternating Maclaurin series, so it stays accurate to a double's last ulps where the naive form loses every digit by `|x| ~ 3`.

```lisp
(linalg:add #(1 2 3) 10)        ; => #d(11.0 12.0 13.0)
(linalg:mul 2 #2A((1 2) (3 4))) ; => #d((2.0 4.0) (6.0 8.0))
(linalg:div #(1 2 3) 2)         ; => #d(0.5 1.0 1.5)
(linalg:sqrt #(4 9 16))         ; => #d(2.0 3.0 4.0)
(linalg:square #2A((1 2) (3 4))) ; => #d((1.0 4.0) (9.0 16.0))
(linalg:mul #2A((1 2) (3 4)) #(10 20))       ; => #d((10.0 40.0) (30.0 80.0))
(linalg:add #2A((1 2) (3 4)) #2A((100) (200))) ; => #d((101.0 102.0) (203.0 204.0))
(linalg:+ #(1 2) #(3 4) #(10 10))            ; => #d(14.0 16.0)
(linalg:- #(5 5))                            ; => #d(-5.0 -5.0)
```

## Reductions along an axis

The reductions [`linalg:sum`](../reference/functions/linalg-sum.md), [`linalg:mean`](../reference/functions/linalg-mean.md), [`linalg:amax`](../reference/functions/linalg-amax.md) and [`linalg:amin`](../reference/functions/linalg-amin.md) take numpy's keyword arguments: an integer `:axis` (negative counts from the end) reduces along that axis instead of over the whole array -- the axis is dropped from the result, or kept as extent 1 when `:keepdims` is non-nil -- the shape that broadcasts back over the input, which is how a batch softmax subtracts its row maxima. [`linalg:argmax`](../reference/functions/linalg-argmax.md) and [`linalg:argmin`](../reference/functions/linalg-argmin.md) take the same `:axis` keyword and return per-slice indices (a packed double array for matrices, since linalg arrays have no integer width). [`linalg:var`](../reference/functions/linalg-var.md) and [`linalg:std`](../reference/functions/linalg-std.md) take the same `:axis` / `:keepdims` plus a `:ddof` divisor correction (`0` by default -- numpy's `np.var` and torch's `unbiased=False`; `1` gives the sample variance), which with `mean` along the same axis is the LayerNorm normalizer. [`linalg:reshape`](../reference/functions/linalg-reshape.md) accepts one `-1` extent and infers it from the element count.

```lisp
(linalg:sum #2A((1 2 3) (4 5 6)) :axis 0)                  ; => #d(5.0 7.0 9.0)
(linalg:sum #2A((1 2 3) (4 5 6)) :axis 1)                  ; => #d(6.0 15.0)
(linalg:sum #2A((1 2 3) (4 5 6)) :axis -1 :keepdims t)     ; => #d((6.0) (15.0))
(linalg:mean #2A((1 2 3) (4 5 6)) :axis 0)                 ; => #d(2.5 3.5 4.5)
(linalg:argmax #2A((1 9 3) (7 5 6)) :axis 1)               ; => #d(1.0 0.0)
(linalg:std #2A((0 1 2) (3 4 5)) :axis 0)                   ; => #d(1.5 1.5 1.5)
(linalg:softmax #2A((0 0) (1 1)) :axis 1)                  ; => #d((0.5 0.5) (0.5 0.5))
(linalg:shape (linalg:reshape (linalg:arange 12) '(3 -1))) ; => (3 4)
```

## Rank-N shapes: joins, slices and stacked products

Everything above is rank-generic, and a handful of operations exist to *reshape* that rank. [`linalg:expand-dims`](../reference/functions/linalg-expand-dims.md) inserts an extent-1 axis and [`linalg:squeeze`](../reference/functions/linalg-squeeze.md) removes one (numpy's `expand_dims` / `squeeze`, torch's `unsqueeze` / `squeeze`); [`linalg:concatenate`](../reference/functions/linalg-concatenate.md) joins a list of arrays along an axis that already exists and [`linalg:stack`](../reference/functions/linalg-stack.md) along a new one; [`linalg:slice`](../reference/functions/linalg-slice.md) is basic numpy slicing, and [`linalg:triu`](../reference/functions/linalg-triu.md) / [`linalg:tril`](../reference/functions/linalg-tril.md) keep one triangle of a matrix.

rontolisp has no `x[:, :n]` syntax, so `slice` spells it as a list with one spec per axis: `nil` leaves that axis whole, `(start end)` or `(start end step)` selects along it, a negative index counts from the end, `nil` in the `start` or `end` position means "from the beginning" / "to the end", and a missing trailing spec leaves the remaining axes whole. Every axis is kept, exactly as numpy's `x[:, 0:3]` keeps both -- dropping an axis is what `linalg:row` does.

[`linalg:matmul`](../reference/functions/linalg-matmul.md) is rank-generic too: at rank >= 3 on either side it is the **stacked** product (torch's `bmm` / `matmul`), where the last two axes are the matrix and every leading axis broadcasts. That is the shape a batched attention score has, so `(batch heads n d)` times `(batch heads d n)` gives `(batch heads n n)` in one call. ([`linalg:dot`](../reference/functions/linalg-dot.md) stays rank <= 2 on purpose: numpy's `np.dot` contracts against a different axis at higher rank, so passing it a stack signals an error pointing here rather than returning a wrong answer.)

```lisp
(linalg:expand-dims #(1 2 3) 0)                  ; => #d((1.0 2.0 3.0))
(linalg:squeeze #2A((1 2 3)))                    ; => #d(1.0 2.0 3.0)
(linalg:concatenate (list #(1 2) #(3)))          ; => #d(1.0 2.0 3.0)
(linalg:stack (list #(1 2) #(3 4)) :axis 1)      ; => #d((1.0 3.0) (2.0 4.0))
(linalg:slice #2A((0 1 2) (3 4 5)) '(nil (0 2))) ; => #d((0.0 1.0) (3.0 4.0))
(linalg:slice #(0 1 2 3 4 5) '((nil nil 2)))     ; => #d(0.0 2.0 4.0)
(linalg:triu (linalg:ones '(3 3)) :k 1)          ; => #d((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0))
(linalg:shape (linalg:matmul (linalg:zeros '(2 3 4))
                             (linalg:zeros '(2 4 5)))) ; => (2 3 5)
```

## Indexing, selection and masks

[`linalg:take-rows`](../reference/functions/linalg-take-rows.md) selects axis-0 slices by an index vector (numpy's `x[mask]`, any rank) and keeps axis 0, while [`linalg:row`](../reference/functions/linalg-row.md) takes one slice by an integer and drops it (numpy's `x[i]`, so one image of a batch arrives at a forward pass as a plain vector). [`linalg:gather`](../reference/functions/linalg-gather.md) picks one element per row (`y[np.arange(n), t]`), and [`linalg:one-hot`](../reference/functions/linalg-one-hot.md) builds a label matrix. The elementwise comparisons [`linalg:equal`](../reference/functions/linalg-equal.md), [`linalg:greater`](../reference/functions/linalg-greater.md), [`linalg:greater-equal`](../reference/functions/linalg-greater-equal.md), [`linalg:less`](../reference/functions/linalg-less.md) and [`linalg:less-equal`](../reference/functions/linalg-less-equal.md) return 0.0/1.0 masks (with scalar operands and broadcasting): multiply by a mask where numpy would boolean-index, or -- better -- pass it to [`linalg:where`](../reference/functions/linalg-where.md), which *selects* between two operands on a non-zero mask (numpy's `np.where`). Selecting rather than multiplying is what lets a `-infinity` mask reach `linalg:softmax` as a weight of exactly zero: multiplying an infinity by zero would give a `NaN`. [`linalg:zeros-like`](../reference/functions/linalg-zeros-like.md) allocates a zero array of the same shape and width.

```lisp
(linalg:take-rows #2A((10 11) (20 21) (30 31)) #(2 0)) ; => #d((30.0 31.0) (10.0 11.0))
(linalg:row #2A((10 11) (20 21) (30 31)) 2)            ; => #d(30.0 31.0)
(linalg:gather #2A((10 11 12) (20 21 22)) #(2 0))      ; => #d(12.0 20.0)
(linalg:one-hot #(1 0) 3)   ; => #d((0.0 1.0 0.0) (1.0 0.0 0.0))
(linalg:greater #(1 5 3) 2) ; => #d(0.0 1.0 1.0)
(linalg:where (linalg:greater #(1 5 3) 2) #(1 5 3) 0) ; => #d(0.0 5.0 3.0)
```

## Random numbers

The `np.random` analog is seeded and cross-backend deterministic: [`linalg:seed`](../reference/functions/linalg-seed.md) resets a Wichmann-Hill generator whose draws are exact integer and IEEE double arithmetic, so a seeded sequence of [`linalg:rand`](../reference/functions/linalg-rand.md), [`linalg:randn`](../reference/functions/linalg-randn.md), [`linalg:uniform`](../reference/functions/linalg-uniform.md), [`linalg:choice`](../reference/functions/linalg-choice.md) and [`linalg:permutation`](../reference/functions/linalg-permutation.md) is bit-identical on the interpreter, the JVM and both WASM targets -- weight initialization and mini-batch sampling reproduce exactly everywhere. `randn` uses the Irwin-Hall sum of twelve uniforms rather than Box-Muller (whose `log`/`cos` would diverge on WASM), so its tails clip at six standard deviations; fine for initialization, but not a distribution-exact `np.random.randn`.

```lisp
(linalg:seed 42)         ; => 42
(linalg:choice 60000 4)  ; => #d(26833.0 11120.0 29256.0 22347.0)
(linalg:permutation 5)   ; => #d(0.0 4.0 2.0 3.0 1.0)
```

## Discrete calculus

[`linalg:diff`](../reference/functions/linalg-diff.md) and [`linalg:gradient`](../reference/functions/linalg-gradient.md) are numpy's discrete-calculus pair (`np.diff` / `np.gradient`). `diff` takes the `:n`-th discrete difference (default 1) along `:axis` (default the last axis): each step shortens that axis by one, so a matrix differences within each row by default and down each column with `:axis 0`. `gradient` estimates the derivative of a vector of samples with second-order central differences (first-order one-sided at the two ends), so the result keeps the input's length; the optional second argument is either a uniform sample spacing (a number, default 1) or a coordinate vector of the same length for non-uniformly spaced samples. Both preserve the input's width like every other linalg transform. The arithmetic is floating point as usual, but sample values that differentiate exactly -- polynomials read at integer coordinates, like every example below -- print identically on every backend.

```lisp
(linalg:diff #(1 2 4 7 0))          ; => #d(1.0 2.0 3.0 -7.0)
(linalg:diff #(1 2 4 7 0) :n 2)     ; => #d(1.0 1.0 -10.0)
(linalg:diff #2A((1 3 6) (0 5 6)))  ; => #d((2.0 3.0) (5.0 1.0))
(linalg:gradient #(0 1 4 9 16))     ; => #d(1.0 2.0 4.0 6.0 7.0)
(linalg:gradient #(0 1 4 9 16) 2)   ; => #d(0.5 1.0 2.0 3.0 3.5)
(linalg:gradient #(0 1 9) #(0 1 3)) ; => #d(1.0 2.0 4.0)
```

The gradient of `#(0 1 4 9 16)` -- the parabola `y = x^2` sampled at `x = 0..4` -- recovers the true derivative `2x` exactly at the interior points (central differences are exact for quadratics; the two ends are first-order estimates), and the coordinate-vector form stays exact even for the unevenly spaced samples on the last line. [`examples/ml/numerical-calculus.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/numerical-calculus.lisp) works these ideas through a projectile-motion walkthrough.

## Single-float precision

linalg computes in `double-float` by default, but it is **width-polymorphic**: it accepts and preserves packed **single-float** (`#f`) arrays, which use half the memory and twice the SIMD lane count. Every constructor takes an `:element-type` keyword (the default is `'double-float`; pass `:element-type 'single-float` for a `#f` result), and every transform -- `add`/`sub`/`mul`/`div`/`emap`, `transpose`/`reshape`, `dot`/`matmul`/`outer`, `inv`/`solve` -- preserves its input's width. A single-float value therefore stays single-float all the way through: a functional weight update `(linalg:sub W grad)` keeps `W`'s width rather than silently widening it back to double (which, on the JVM [`--simd`](simd-acceleration.md) path, would force a mixed-width error on the following `vec:matvec`). Reach for single-float when you want the speed and memory of `f32` and can accept its lower precision, and keep the double-float default for precision-critical work such as `det`/`inv`/`solve`.

```lisp
(linalg:zeros 3 :element-type 'single-float)                   ; => #f(0.0 0.0 0.0)
(linalg:from-list '((1 2) (3 4)) :element-type 'single-float)  ; => #f((1.0 2.0) (3.0 4.0))
(linalg:add (linalg:from-list '(1 2 3) :element-type 'single-float) 10) ; => #f(11.0 12.0 13.0)
(array-element-type
  (linalg:transpose (linalg:eye 2 :element-type 'single-float)))        ; => SINGLE-FLOAT
```

## SIMD acceleration

`linalg` needs no flag to be correct anywhere, but the [`--simd` flag](simd-acceleration.md) accelerates it: thirty-two functions -- `add`, `sub`, `mul`, `div`, `sum`, `norm`, `amax`, `amin`, `argmax`, `argmin`, `trace`, `transpose`, `reshape`, `dot`, `outer`, the unary ufuncs `exp`, `log`, `tanh`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `sqrt`, `abs`, `negative`, `sign`, and the comparison selects `maximum`, `minimum` -- are routed to native vector kernels (`jdk.incubator.vector` on the interpreter and the JVM, WebAssembly `v128` on wasm-GC), and `mean`, `matmul`, `flatten`, `solve`, `square`, `reciprocal`, `clip` and `relu` are accelerated with them because they are written in terms of them. Nothing changes in what a program accepts or rejects: an input a kernel cannot handle (a general boxed array, mixed widths, a plain number) simply runs the portable `linalg.lisp` definition instead, with the same result and the same error messages. The only observable difference is the [single-float precision rule](simd-acceleration.md#accelerating-linalg), which covers the reductions and the matrix product; element-wise results stay bit-identical. That last sentence is `--simd`'s alone: [`--gpu`](simd-acceleration.md#accelerating-the-matrix-product-and-the-transcendentals-on-a-gpu---gpu) also takes the element-wise transcendentals, and a device computes them with its own library, so under that flag they are close to the portable definition rather than equal to it.

There is no reason to switch packages for speed: under `--simd`, `linalg` and `vec` land on the same kernels. See [Choosing between vec and linalg](simd-acceleration.md#choosing-between-vec-and-linalg) -- the short version is: write against `linalg` by default, and reach for `vec` only for its `-into` destination-passing loops, a `--no-gc` target (where `linalg` does not compile), or its fail-fast width strictness.

## First-class functions

linalg functions are ordinary `defun`s, so `#'linalg:norm` and friends work as first-class values wherever a function is expected:

```lisp
(mapcar #'linalg:norm (list #(3 4) #(6 8))) ; => (5.0 10.0)
```

Because arrays compare by identity (`eq`) only, results are compared with [`linalg:array-equal`](../reference/functions/linalg-array-equal.md), which checks shape and numeric equality (`1` and `1.0` compare equal).

## Packages

`linalg` is a [package](../reference/packages.md) of its own and does not use `cl`: inside `(in-package linalg)` the standard functions are not visible under their bare names and would need `cl:` qualification (`cl:print`, `cl:mapcar`, ...). Most programs should therefore stay in the default `cl-user` package and call the qualified `linalg:` names, as every example on this page does.
