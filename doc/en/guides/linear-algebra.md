# Vectors & Matrices (linalg)

The `linalg` package provides a numpy-style API for vectors and matrices: constructors, shape manipulation, elementwise arithmetic, products, reductions, and linear algebra (determinant, inverse, linear solving).

Like the JSON library, `linalg` is implemented once in Lisp source (`linalg.lisp`): the interpreter loads the definitions lazily on the first use of a `linalg:` function, and the compile path splices them into the program when it references the package. There is no per-backend code, so every function behaves identically on the interpreter, the JVM compiler, WASM Preview 1 and the WASM component.

## Data representation

linalg constructors build [packed float arrays](../reference/data-types.md): unboxed `(array double-float)` values, the same representation as an `#f(...)` literal. A vector is a rank-1 array, printed `#(...)`, and a matrix is a rank-2 array, printed `#2A(...)`. Individual elements are read and written with `aref`, and any array built elsewhere -- packed or a general boxed array -- can be handed to a linalg function. Arrays of higher rank work too: the elementwise operations, the reductions, `reshape`/`flatten` and `array-equal` walk the elements in flat row-major order and accept any rank, while `dot`/`matmul`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` stay defined for vectors and matrices (rank <= 2), like numpy's specialized routines. [`linalg:from-list`](../reference/functions/linalg-from-list.md) / [`linalg:to-list`](../reference/functions/linalg-to-list.md) convert between arrays and lists.

linalg computes in floating point, prioritizing speed: every constructor and array-building operation returns a packed double-float array, and [`linalg:det`](../reference/functions/linalg-det.md), [`linalg:inv`](../reference/functions/linalg-inv.md) and [`linalg:solve`](../reference/functions/linalg-solve.md) run in floating point (like numpy), so a general inverse carries the usual rounding and a nearly singular determinant can be a small epsilon rather than exactly `0`. A reduction follows the element type numpy-style: a reduction over a packed or float array is a double, while a reduction over a plain integer array (a bare `#(1 2 3)` literal) stays an integer or exact ratio; [`linalg:norm`](../reference/functions/linalg-norm.md) is always a float because `sqrt` is. One cross-backend caveat: the WASM backends print a non-terminating float at fewer significant digits than the interpreter and JVM, so a rounded inverse or an irrational norm can look different across backends even though the underlying `double` is identical.

## A worked example

```lisp
(linalg:eye 3)                          ; => #2A((1.0 0.0 0.0) (0.0 1.0 0.0) (0.0 0.0 1.0))
(linalg:arange 5)                       ; => #(0.0 1.0 2.0 3.0 4.0)
(linalg:linspace 0 1 5)                 ; => #(0.0 0.25 0.5 0.75 1.0)
(linalg:matmul #2A((1 2) (3 4))
               #2A((5 6) (7 8)))        ; => #2A((19.0 22.0) (43.0 50.0))
(linalg:det #2A((1 2) (3 4)))           ; => -2.0
(linalg:inv #2A((4 0) (2 4)))           ; => #2A((0.25 0.0) (-0.125 0.25))
(linalg:solve #2A((4 0) (2 4)) #(8 8))  ; => #(2.0 1.0)
```

The `inv` and `solve` matrices above are chosen so their float results are exact and print identically on every backend; a general inverse such as `(linalg:inv #2A((1 2) (3 4)))` computes the same values but carries floating-point rounding.

## Elementwise arithmetic and broadcasting

[`linalg:add`](../reference/functions/linalg-add.md), [`linalg:sub`](../reference/functions/linalg-sub.md), [`linalg:mul`](../reference/functions/linalg-mul.md) and [`linalg:div`](../reference/functions/linalg-div.md) operate elementwise, and a scalar operand on either side is broadcast over the other operand's shape; two array operands must have equal shapes. Note that `mul` is the Hadamard (elementwise) product -- the matrix product is [`linalg:matmul`](../reference/functions/linalg-matmul.md) (or the rank-dispatching [`linalg:dot`](../reference/functions/linalg-dot.md)). Arbitrary per-element transformations go through [`linalg:emap`](../reference/functions/linalg-emap.md).

```lisp
(linalg:add #(1 2 3) 10)        ; => #(11.0 12.0 13.0)
(linalg:mul 2 #2A((1 2) (3 4))) ; => #2A((2.0 4.0) (6.0 8.0))
(linalg:div #(1 2 3) 2)         ; => #(0.5 1.0 1.5)
```

## First-class functions

linalg functions are ordinary `defun`s, so `#'linalg:norm` and friends work as first-class values wherever a function is expected:

```lisp
(mapcar #'linalg:norm (list #(3 4) #(6 8))) ; => (5.0 10.0)
```

Because arrays compare by identity (`eq`) only, results are compared with [`linalg:array-equal`](../reference/functions/linalg-array-equal.md), which checks shape and numeric equality (`1` and `1.0` compare equal).

## Packages

`linalg` is a [package](../reference/packages.md) of its own and does not use `cl`: inside `(in-package linalg)` the standard functions are not visible under their bare names and would need `cl:` qualification (`cl:print`, `cl:mapcar`, ...). Most programs should therefore stay in the default `cl-user` package and call the qualified `linalg:` names, as every example on this page does.
