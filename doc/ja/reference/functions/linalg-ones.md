# linalg:ones

`(linalg:ones shape &key element-type)`

要素がすべて 1 の配列を作成します。`shape` は [`linalg:zeros`](linalg-zeros.md) と同様に、ランク 1 のベクタの場合は整数、ランク 2 の行列の場合はリスト `(rows cols)` です。任意の値で埋めるには [`linalg:full`](linalg-full.md) を使ってください。 デフォルトは packed double-float で、`:element-type 'single-float` を渡すと packed single-float (`#f`) になります。

```lisp
(linalg:ones '(2 2)) ; => #d((1.0 1.0) (1.0 1.0))
(linalg:ones 2 :element-type 'single-float) ; => #f(1.0 1.0)
```
