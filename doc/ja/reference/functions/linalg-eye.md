# linalg:eye

`(linalg:eye n)`

`n` 行 `n` 列の単位行列 (主対角線が 1、それ以外がすべて 0) を作成します。[`linalg:matmul`](linalg-matmul.md) で掛けても行列は変化せず、[`linalg:array-equal`](linalg-array-equal.md) の比較対象としても便利です。

```lisp
(linalg:eye 3) ; => #2A((1.0 0.0 0.0) (0.0 1.0 0.0) (0.0 0.0 1.0))
```
