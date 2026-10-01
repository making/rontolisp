# keys

`(keys m)`

マップのキーのリストを、表の走査順 -- 未規定 -- で返します。`nil` の `keys` は `nil` です。

Deviation: マップでないものへの誤用は、oracle の代わりに Common Lisp の型エラーを上げることがあります。

```clojure
(println (keys {:a 1}))          ; (:a)
(println (count (keys {:a 1 :b 2}))) ; 2
(println (keys nil))             ; nil
```
