# keys

`(keys m)`

マップのキーのリストを挿入順 -- マップの印字と同じ順（8 エントリまではオラクルの array map の順）-- で返します。`nil` の `keys` は `nil` です。

値としては 1 引数のラムダです。

仕様との差異: マップでないものへの誤用は、オラクルの代わりに Common Lisp の型エラーを上げることがあります。

```clojure
(println (keys {:a 1}))          ; (:a)
(println (keys {:a 1 :b 2}))    ; (:a :b)
(println (keys nil))             ; nil
(println (map keys [{:a 1}]))    ; ((:a))
```
