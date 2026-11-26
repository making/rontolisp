# amap

`(amap array index result expr)`

`expr` を `array` に対して添字で写像します:配列を 1 回だけ評価して写しを作り、その写
しの各要素を `expr` の答えに置き換えて、その写しを答えます。`index` はループの添字、
`result` は写しを指す名前で、どちらもこの形の中でのみ有効です。ここの[配列](int-array.md)はベクターなので、答えもベクターとして印字されます。

```clojure
(def am-a (int-array [1 2 3]))
(println (vec (amap am-a i r (* 2 (aget r i))))) ; [2 4 6]
(println (vec am-a)) ; [1 2 3]
```
