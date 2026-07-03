# linalg:emap

`(linalg:emap function array)`

すべての要素に `function` を適用した、同じ形状の新しい配列を返します。`mapcar` の配列版です。ベクタと行列のどちらにも動作し、任意の関数値（`lambda`、`#'` で参照した組み込み関数、`#'` で参照した linalg 関数）を受け付けます。よくある 2 オペランドのケースは、2 項の要素ごとの演算子 [`linalg:add`](linalg-add.md)、[`linalg:sub`](linalg-sub.md)、[`linalg:mul`](linalg-mul.md)、[`linalg:div`](linalg-div.md) がカバーします。

```lisp
(linalg:emap (lambda (x) (* x x)) (linalg:arange 4)) ; => #(0 1 4 9)
```
