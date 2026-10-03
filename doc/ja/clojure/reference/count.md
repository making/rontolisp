# count

`(count coll)`

`coll` の要素数を返します。表を認識します。マップとセットは `hash-table-count` を直接返し（seq を組み立てません）、それ以外は seq ビューの長さです（lazy seq は先にすべて realize します）。

```clojure
(println (count '(1 2 3)))   ; 3
(println (count [1 2 3]))    ; 3
(println (count {:a 1 :b 2})) ; 2
(println (count #{1 2 3}))   ; 3
(println (count false))      ; 0
```
