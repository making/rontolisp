# count

`(count coll)`

`coll` の要素数を返します。表を認識します。マップとセットは `hash-table-count` を直接返し（seq を組み立てません）、それ以外は seq ビューの長さです（lazy seq は先にすべて realize します）。Java の `Collection` と `Map` は `size` を、`CharSequence` は `length` を返し、それ以外の Java の `Iterable` はオラクルと同様にシグナルします。コレクションでないもの（キーワード、`false`、数、アトム）はオラクルと同様に `UnsupportedOperationException` をシグナルします。

```clojure
(println (count '(1 2 3)))   ; 3
(println (count [1 2 3]))    ; 3
(println (count {:a 1 :b 2})) ; 2
(println (count #{1 2 3}))   ; 3
(println (count nil))        ; 0
```
