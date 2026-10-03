# second

`(second coll)`

`coll` の seq ビューの2番目の要素を返し、なければ `nil` です。`(nth coll 1)` と同じなので、lazy 入力は先頭の2要素しか realize しません。値としては1引数のラムダです。

```clojure
(println (second [1 2 3])) ; 2
```
