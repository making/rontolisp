# linalg:eye

`(linalg:eye n &key element-type)`

Creates the `n`-by-`n` identity matrix: ones on the main diagonal, zeros everywhere else. Multiplying by it with [`linalg:matmul`](linalg-matmul.md) leaves a matrix unchanged, and it is a convenient reference operand for [`linalg:array-equal`](linalg-array-equal.md). Double-float by default; pass `:element-type 'single-float` for a packed single-float (`#f`) result. `:element-type 'bfloat16` builds a packed bfloat16 (`#bf16`) array instead, on the interpreter and the JVM only ([Single-float precision](../../guides/linear-algebra.md#single-float-precision)).

```lisp
(linalg:eye 3) ; => #d((1.0 0.0 0.0) (0.0 1.0 0.0) (0.0 0.0 1.0))
(linalg:eye 2 :element-type 'single-float) ; => #f((1.0 0.0) (0.0 1.0))
```
