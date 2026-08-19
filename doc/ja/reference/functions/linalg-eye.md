# linalg:eye

`(linalg:eye n &key element-type)`

`n` 行 `n` 列の単位行列 (主対角線が 1、それ以外がすべて 0) を作成します。[`linalg:matmul`](linalg-matmul.md) で掛けても行列は変化せず、[`linalg:array-equal`](linalg-array-equal.md) の比較対象としても便利です。 デフォルトは packed double-float で、`:element-type 'single-float` を渡すと packed single-float (`#f`) になります。

```lisp
(linalg:eye 3) ; => #d((1.0 0.0 0.0) (0.0 1.0 0.0) (0.0 0.0 1.0))
(linalg:eye 2 :element-type 'single-float) ; => #f((1.0 0.0) (0.0 1.0))
```
