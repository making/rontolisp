# Vectors & Matrices (linalg)

The `linalg` package provides a numpy-style API for vectors and matrices: constructors, shape manipulation, elementwise arithmetic, products, reductions, and exact linear algebra (determinant, inverse, linear solving).

Like the JSON library, `linalg` is implemented once in Lisp source (`linalg.lisp`): the interpreter loads the definitions lazily on the first use of a `linalg:` function, and the compile path splices them into the program when it references the package. There is no per-backend code, so every function behaves identically on the interpreter, the JVM compiler, WASM Preview 1 and the WASM component.

## Data representation

linalg arrays are the built-in arrays created by `make-array`: a vector is a rank-1 array, printed `#(...)`, and a matrix is a rank-2 array, printed `#2A(...)`. Individual elements are read and written with `aref`, and any array built elsewhere in the program can be handed to a linalg function. Arrays of higher rank work too: the elementwise operations, the reductions, `reshape`/`flatten` and `array-equal` walk the elements in flat row-major order and accept any rank, while `dot`/`matmul`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` stay defined for vectors and matrices (rank <= 2), like numpy's specialized routines. [`linalg:from-list`](../reference/functions/linalg-from-list.md) / [`linalg:to-list`](../reference/functions/linalg-to-list.md) convert between arrays and lists.

Arithmetic is generic and exact: integer inputs stay integers and ratios rather than degrading to floats, so [`linalg:det`](../reference/functions/linalg-det.md), [`linalg:inv`](../reference/functions/linalg-inv.md) and [`linalg:solve`](../reference/functions/linalg-solve.md) of an integer matrix are exact -- a singular matrix has a determinant of exactly `0`, never a float epsilon. Float inputs propagate as floats, and [`linalg:norm`](../reference/functions/linalg-norm.md) returns a float because `sqrt` does.

## A worked example

```lisp
(linalg:eye 3)                                   ; => #2A((1 0 0) (0 1 0) (0 0 1))
(linalg:arange 5)                                ; => #(0 1 2 3 4)
(linalg:linspace 0 1 5)                          ; => #(0 1/4 1/2 3/4 1)
(let ((a (linalg:from-list '((1 2) (3 4))))
      (b (linalg:from-list '((5 6) (7 8)))))
  (linalg:matmul a b))                           ; => #2A((19 22) (43 50))
(linalg:det (linalg:from-list '((1 2) (3 4))))   ; => -2
(linalg:inv (linalg:from-list '((1 2) (3 4))))   ; => #2A((-2 1) (3/2 -1/2))
(linalg:solve (linalg:from-list '((2 1) (1 3)))
              #(3 5))                            ; => #(4/5 7/5)
```

## Elementwise arithmetic and broadcasting

[`linalg:add`](../reference/functions/linalg-add.md), [`linalg:sub`](../reference/functions/linalg-sub.md), [`linalg:mul`](../reference/functions/linalg-mul.md) and [`linalg:div`](../reference/functions/linalg-div.md) operate elementwise, and a scalar operand on either side is broadcast over the other operand's shape; two array operands must have equal shapes. Note that `mul` is the Hadamard (elementwise) product -- the matrix product is [`linalg:matmul`](../reference/functions/linalg-matmul.md) (or the rank-dispatching [`linalg:dot`](../reference/functions/linalg-dot.md)). Arbitrary per-element transformations go through [`linalg:emap`](../reference/functions/linalg-emap.md).

```lisp
(linalg:add #(1 2 3) 10)                         ; => #(11 12 13)
(linalg:mul 2 (linalg:from-list '((1 2) (3 4)))) ; => #2A((2 4) (6 8))
(linalg:div #(1 2 3) 2)                          ; => #(1/2 1 3/2)
```

## First-class functions

linalg functions are ordinary `defun`s, so `#'linalg:norm` and friends work as first-class values wherever a function is expected:

```lisp
(mapcar #'linalg:norm (list #(3 4) #(6 8))) ; => (5.0 10.0)
```

Because arrays compare by identity (`eq`) only, results are compared with [`linalg:array-equal`](../reference/functions/linalg-array-equal.md), which checks shape and numeric equality (`1` and `1.0` compare equal).

## Packages

`linalg` is a [package](../reference/packages.md) of its own and does not use `cl`: inside `(in-package linalg)` the standard functions are not visible under their bare names and would need `cl:` qualification (`cl:print`, `cl:mapcar`, ...). Most programs should therefore stay in the default `cl-user` package and call the qualified `linalg:` names, as every example on this page does.
