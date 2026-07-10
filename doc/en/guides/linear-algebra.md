# Vectors & Matrices (linalg)

The `linalg` package provides a numpy-style API for vectors and matrices: constructors, shape manipulation, elementwise arithmetic, products, reductions, and linear algebra (determinant, inverse, linear solving).

Like the JSON library, `linalg` is implemented once in Lisp source (`linalg.lisp`): the interpreter loads the definitions lazily on the first use of a `linalg:` function, and the compile path splices them into the program when it references the package. There is no per-backend code, so every function behaves identically on the interpreter, the JVM compiler, WASM Preview 1 and the WASM component.

## Data representation

linalg constructors build [packed float arrays](../reference/data-types.md): unboxed `(array double-float)` values, the same representation as an `#d(...)` literal. A vector is a rank-1 array, printed `#d(1.0 2.0 ...)`, and a matrix is a rank-2 array, printed with the nested `#d((...) ...)` form -- the `#d` marks the unboxed packed representation, so the printed form reads back as a packed array. Individual elements are read and written with `aref`, and any array built elsewhere -- packed or a general boxed array -- can be handed to a linalg function. Arrays of higher rank work too: the elementwise operations, the reductions, `reshape`/`flatten` and `array-equal` walk the elements in flat row-major order and accept any rank, while `dot`/`matmul`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` stay defined for vectors and matrices (rank <= 2), like numpy's specialized routines. [`linalg:from-list`](../reference/functions/linalg-from-list.md) / [`linalg:to-list`](../reference/functions/linalg-to-list.md) convert between arrays and lists.

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

## Elementwise arithmetic and broadcasting

[`linalg:add`](../reference/functions/linalg-add.md), [`linalg:sub`](../reference/functions/linalg-sub.md), [`linalg:mul`](../reference/functions/linalg-mul.md) and [`linalg:div`](../reference/functions/linalg-div.md) operate elementwise, and a scalar operand on either side is broadcast over the other operand's shape; two array operands must have equal shapes. Note that `mul` is the Hadamard (elementwise) product -- the matrix product is [`linalg:matmul`](../reference/functions/linalg-matmul.md) (or the rank-dispatching [`linalg:dot`](../reference/functions/linalg-dot.md)). Arbitrary per-element transformations go through [`linalg:emap`](../reference/functions/linalg-emap.md).

The frequent per-element operations also exist under their numpy ufunc names: [`linalg:exp`](../reference/functions/linalg-exp.md), [`linalg:log`](../reference/functions/linalg-log.md), [`linalg:tanh`](../reference/functions/linalg-tanh.md), [`linalg:sin`](../reference/functions/linalg-sin.md), [`linalg:cos`](../reference/functions/linalg-cos.md), [`linalg:tan`](../reference/functions/linalg-tan.md), [`linalg:asin`](../reference/functions/linalg-asin.md), [`linalg:acos`](../reference/functions/linalg-acos.md), [`linalg:atan`](../reference/functions/linalg-atan.md), [`linalg:sinh`](../reference/functions/linalg-sinh.md), [`linalg:cosh`](../reference/functions/linalg-cosh.md), [`linalg:sqrt`](../reference/functions/linalg-sqrt.md), [`linalg:abs`](../reference/functions/linalg-abs.md), [`linalg:square`](../reference/functions/linalg-square.md), [`linalg:negative`](../reference/functions/linalg-negative.md), [`linalg:sign`](../reference/functions/linalg-sign.md) and [`linalg:reciprocal`](../reference/functions/linalg-reciprocal.md). Each is equivalent to the obvious `emap` (or `mul` / `div` call), but as named functions they are accelerated under [`--simd`](simd-acceleration.md#accelerating-linalg), which `emap` with an arbitrary callback never is.

```lisp
(linalg:add #(1 2 3) 10)        ; => #d(11.0 12.0 13.0)
(linalg:mul 2 #2A((1 2) (3 4))) ; => #d((2.0 4.0) (6.0 8.0))
(linalg:div #(1 2 3) 2)         ; => #d(0.5 1.0 1.5)
(linalg:sqrt #(4 9 16))         ; => #d(2.0 3.0 4.0)
(linalg:square #2A((1 2) (3 4))) ; => #d((1.0 4.0) (9.0 16.0))
```

## Single-float precision

linalg computes in `double-float` by default, but it is **width-polymorphic**: it accepts and preserves packed **single-float** (`#f`) arrays, which use half the memory and twice the SIMD lane count. Every constructor takes an optional trailing `element-type` (the default is `'double-float`; pass `'single-float` for a `#f` result), and every transform -- `add`/`sub`/`mul`/`div`/`emap`, `transpose`/`reshape`, `dot`/`matmul`/`outer`, `inv`/`solve` -- preserves its input's width. A single-float value therefore stays single-float all the way through: a functional weight update `(linalg:sub W grad)` keeps `W`'s width rather than silently widening it back to double (which, on the JVM [`--simd`](simd-acceleration.md) path, would force a mixed-width error on the following `vec:matvec`). Reach for single-float when you want the speed and memory of `f32` and can accept its lower precision, and keep the double-float default for precision-critical work such as `det`/`inv`/`solve`.

```lisp
(linalg:zeros 3 'single-float)                   ; => #f(0.0 0.0 0.0)
(linalg:from-list '((1 2) (3 4)) 'single-float)  ; => #f((1.0 2.0) (3.0 4.0))
(linalg:add (linalg:from-list '(1 2 3) 'single-float) 10) ; => #f(11.0 12.0 13.0)
(array-element-type
  (linalg:transpose (linalg:eye 2 'single-float)))        ; => single-float
```

## SIMD acceleration

`linalg` needs no flag to be correct anywhere, but the [`--simd` flag](simd-acceleration.md) accelerates it: thirty functions -- `add`, `sub`, `mul`, `div`, `sum`, `norm`, `amax`, `amin`, `argmax`, `argmin`, `trace`, `transpose`, `reshape`, `dot`, `outer` and the unary ufuncs `exp`, `log`, `tanh`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `sqrt`, `abs`, `negative`, `sign` -- are routed to native vector kernels (`jdk.incubator.vector` on the interpreter and the JVM, WebAssembly `v128` on wasm-GC), and `mean`, `matmul`, `flatten`, `solve`, `square` and `reciprocal` are accelerated with them because they are written in terms of them. Nothing changes in what a program accepts or rejects: an input a kernel cannot handle (a general boxed array, mixed widths, a plain number) simply runs the portable `linalg.lisp` definition instead, with the same result and the same error messages. The only observable difference is the [single-float reduction precision rule](simd-acceleration.md#accelerating-linalg); element-wise results and the full matrix product stay bit-identical.

There is no reason to switch packages for speed: under `--simd`, `linalg` and `vec` land on the same kernels. See [Choosing between vec and linalg](simd-acceleration.md#choosing-between-vec-and-linalg) -- the short version is: write against `linalg` by default, and reach for `vec` only for its `-into` destination-passing loops, a `--no-gc` target (where `linalg` does not compile), or its fail-fast width strictness.

## First-class functions

linalg functions are ordinary `defun`s, so `#'linalg:norm` and friends work as first-class values wherever a function is expected:

```lisp
(mapcar #'linalg:norm (list #(3 4) #(6 8))) ; => (5.0 10.0)
```

Because arrays compare by identity (`eq`) only, results are compared with [`linalg:array-equal`](../reference/functions/linalg-array-equal.md), which checks shape and numeric equality (`1` and `1.0` compare equal).

## Packages

`linalg` is a [package](../reference/packages.md) of its own and does not use `cl`: inside `(in-package linalg)` the standard functions are not visible under their bare names and would need `cl:` qualification (`cl:print`, `cl:mapcar`, ...). Most programs should therefore stay in the default `cl-user` package and call the qualified `linalg:` names, as every example on this page does.
