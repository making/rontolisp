# deref

`(deref ref)`

アトムか volatile を読みます。リーダー形式 `@x` は同じ操作です。関数値として動くため、`map`/`reduce` に裸のまま渡せます。

```clojure
(def a (atom 1))
(println (deref a)) ; 1
(println @a) ; 1
(println (map deref [(atom 1) (atom 2)])) ; (1 2)
```
