# keys

`(keys m)`

マップのキーのリストを、新しいものから順 -- マップの印字の逆順 -- で返します。`nil` の `keys` は `nil` です。

値としては 1 引数のラムダです。

仕様との差異: マップでないものへの誤用は、オラクルの代わりに Common Lisp の型エラーを上げることがあります。

```clojure
(println (keys {:a 1}))          ; (:a)
(println (count (keys {:a 1 :b 2}))) ; 2
(println (keys nil))             ; nil
(println (map keys [{:a 1}]))    ; ((:a))
```
