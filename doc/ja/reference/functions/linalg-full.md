# linalg:full

`(linalg:full shape value)`

要素がすべて `value` の配列を作成します。`shape` はランク 1 のベクタの場合は整数、ランク 2 の行列の場合はリスト `(rows cols)` です。[`linalg:zeros`](linalg-zeros.md) と [`linalg:ones`](linalg-ones.md) はそれぞれ 0 と 1 の特殊ケースです。

```lisp
(linalg:full '(2 2) 7) ; => #f((7.0 7.0) (7.0 7.0))
```
