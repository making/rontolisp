# linalg:gather

`(linalg:gather matrix indices)`

行列の行ごとの要素 `a[i, idx[i]]` を、入力と同じ幅のベクタとして返します (numpy の `y[np.arange(n), t]` fancy indexing イディオム。交差エントロピー損失で正解クラスの確率を取り出す場面などに使います)。インデックス値は整数に truncate されます。行列でない入力や、インデックスの長さが行数と一致しない場合はエラーを通知します。行を丸ごと選ぶには [`linalg:take-rows`](linalg-take-rows.md) を使ってください。

```lisp
(linalg:gather #2A((10 11 12) (20 21 22)) #(2 0)) ; => #d(12.0 20.0)
```
