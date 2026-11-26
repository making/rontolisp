# linalg:matmul

`(linalg:matmul a b)`

The matrix product of `a` and `b` (numpy's `np.matmul`, the `@` operator). At rank <= 2 it behaves like [`linalg:dot`](linalg-dot.md) -- matrix . vector included -- but signals an error when either operand is a scalar, catching the mistake of writing a matrix product where an elementwise [`linalg:mul`](linalg-mul.md) would silently apply. The inner dimensions must agree; a mismatch signals an error.

At rank >= 3 on either side it is the **stacked** matrix product (torch's `bmm` / `matmul`): the last two axes are the matrix and every leading axis broadcasts by the numpy rules, so a `(batch heads n d)` query times a `(batch heads d n)` key gives `(batch heads n n)` attention scores. A rank-1 operand is promoted for the product -- a row on the left, a column on the right -- and its axis is dropped again from the result, exactly as in numpy.

```lisp
(linalg:matmul #2A((1 2) (3 4))
               #2A((5 6) (7 8)))                          ; => #d((19.0 22.0) (43.0 50.0))
(linalg:shape (linalg:matmul (linalg:zeros '(2 3 4))
                             (linalg:zeros '(2 4 5))))    ; => (2 3 5)
(linalg:matmul (linalg:reshape (linalg:arange 8) '(2 2 2))
               #2A((1 0) (0 1)))                          ; => #d(((0.0 1.0) (2.0 3.0)) ((4.0 5.0) (6.0 7.0)))
```
