# linalg:full

`(linalg:full shape value &key element-type)`

要素がすべて `value` の配列を作成します。`shape` はランク 1 のベクタの場合は整数、ランク 2 の行列の場合はリスト `(rows cols)` です。[`linalg:zeros`](linalg-zeros.md) と [`linalg:ones`](linalg-ones.md) はそれぞれ 0 と 1 の特殊ケースです。 デフォルトは packed double-float で、`:element-type 'single-float` を渡すと packed single-float (`#f`) になります。

```lisp
(linalg:full '(2 2) 7) ; => #d((7.0 7.0) (7.0 7.0))
(linalg:full 2 0.5 :element-type 'single-float) ; => #f(0.5 0.5)
```
