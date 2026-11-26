# linalg:take-rows

`(linalg:take-rows array indices)`

インデックスベクタ `indices` で選んだ axis-0 のスライスを、入力と同じ幅の新しい配列として返します (numpy の `x[mask]` / `np.take(a, idx, axis=0)`)。スライス (slab) を行優先で丸ごとコピーするため、rank >= 1 の任意の rank で動作します。インデックス値は整数に truncate され、同じインデックスを重複して指定できます。[`linalg:choice`](linalg-choice.md) や [`linalg:permutation`](linalg-permutation.md) の結果と組み合わせるとミニバッチ抽出になります。行ごとの要素の取り出しは [`linalg:gather`](linalg-gather.md) です。インデックスが 1 つでも axis 0 は残ります(`#(2)` は `(1 n)` の行列を返します。numpy の `x[[2]]`)。1 スライスだけ取り出して axis を落とすには [`linalg:row`](linalg-row.md) を使ってください。

```lisp
(linalg:take-rows #2A((1 2 3) (4 5 6) (7 8 9)) #(2 0)) ; => #d((7.0 8.0 9.0) (1.0 2.0 3.0))
```
