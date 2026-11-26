# linalg:row

`(linalg:row array index)`

`array` の axis-0 スライス `index` を、axis 0 を **落として** 入力と同じ幅の新しい配列として返します (numpy の整数インデックス `x[i]`)。行列なら行ベクタが、rank-4 のバッチなら rank-3 のサンプルが得られます。インデックス値は整数に truncate されます。

これは [`linalg:take-rows`](linalg-take-rows.md) の 1 スライス版です。`take-rows` は axis 0 を残す (numpy の `x[[i]]`。行列は `(1 n)` の行列のまま) のに対し、`linalg:row` は axis 0 を落とします。numpy が `x[i]` と書く場面 (バッチから 1 枚の画像を取り出して順伝播に渡す、など) では `linalg:row` を、単一要素の読み出しには `aref` を使ってください。`array` は rank >= 2 である必要があります。ベクタに対しては `(aref v i)` が既に要素を返すため、`linalg:row` はエラーを送出します。

```lisp
(linalg:row #2A((1 2 3) (4 5 6) (7 8 9)) 1) ; => #d(4.0 5.0 6.0)
```
