# torch:gather

`(torch:gather a idx)`

行列の微分可能な行ごとの選択です。各行 `i` について要素 `a[i, idx[i]]` をベクトルとして返します (`linalg:gather` -- 交差エントロピー損失の「正解ロジットを選ぶ」イディオム)。`idx` はインデックスベクトル、リスト、テンソルのいずれでも構いません。backward は選ばれたセルに勾配を散布して戻します。

```lisp
(torch:data (torch:gather (torch:tensor '((1.0 2.0 3.0) (4.0 5.0 6.0))) #(2 0))) ; => #f(3.0 4.0)
```
