# second

`(second coll)`

`coll` の seq ビューの2番目の要素を返し、なければ `nil` です。lazy 入力は先頭の2要素しか realize せず、マップとセットは2番目のエントリか要素を返します（`nth` はこれらを拒否します）。値としては1引数のラムダです。

```clojure
(println (second [1 2 3])) ; 2
```
