# linalg:cross

`(linalg:cross a b)`

3 次元の外積（クロス積）です（numpy の `np.cross` から `axis`/`axisa`/`axisb`/`axisc` によるブロードキャストを除いたもの）。長さ 3 のベクタ 2 つを渡すと、長さ 3 のクロス積を返し、[`linalg:add`](linalg-add.md) と同様に `a` の要素幅を保ちます。長さ 2 のベクタ 2 つを渡すと、第 3 座標を 0 として拡張したベクタのクロス積の z 成分（スカラー）を返します -- これは numpy 自身の 2 次元の場合と同じです。それ以外の rank や長さはすべて形状エラーを通知します。

```lisp
(linalg:cross #(1 0 0) #(0 1 0)) ; => #d(0.0 0.0 1.0)
(linalg:cross #(1 2 3) #(4 5 6)) ; => #d(-3.0 6.0 -3.0)
(linalg:cross #(1 2) #(3 4))     ; => -2
```
