# fnext

`(fnext coll)`

`coll` の `next` の先頭です。末尾は seq ビューを通ります: `(first (next coll))`。
値としては1引数ラムダです。

```clojure
(println (fnext [1 2 3])) ; 2
(println (fnext [1])) ; nil
```
