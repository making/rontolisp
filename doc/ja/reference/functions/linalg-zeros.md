# linalg:zeros

`(linalg:zeros shape &key element-type)`

要素がすべて 0 の配列を作成します。`shape` が整数の場合はその長さのランク 1 のベクタ、2 つの整数のリスト `(rows cols)` の場合はランク 2 の行列になります -- これは [`linalg:ones`](linalg-ones.md) と [`linalg:full`](linalg-full.md) でも使われる共通の shape 規約です。パッケージの概要は [linalg ガイド](../../guides/linear-algebra.md) を参照してください。 デフォルトは packed double-float で、`:element-type 'single-float` を渡すと packed single-float (`#f`) になります。

```lisp
(linalg:zeros 3)      ; => #d(0.0 0.0 0.0)
(linalg:zeros '(2 2)) ; => #d((0.0 0.0) (0.0 0.0))
(linalg:zeros 2 :element-type 'single-float) ; => #f(0.0 0.0)
```
