# qualified-keyword?

`(qualified-keyword? x)`

`clojure.core/qualified-keyword?`: 名前空間のあるキーワードなら `true` を返します。`::kw` も含みます。値としては1引数の関数です。

```clojure
(println (qualified-keyword? :a/b) (qualified-keyword? ::k) (qualified-keyword? :a))  ; true true false
```
