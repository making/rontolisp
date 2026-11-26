# zipmap

`(zipmap keys vals)`

キーと値を組み合わせた新しいマップを返します。短い方で止まるのはオラクル同様で、後のキーが
勝ちます。値としては2引数のラムダです。

```clojure
(println (zipmap [:a] [1 2])) ; {:a 1}
```
