# keyword?

`(keyword? x)`

`clojure.core/keyword?`: 修飾の有無によらずキーワードなら `true` を返します。値としては1引数の関数です。

```clojure
(println (keyword? :a) (keyword? :a/b) (keyword? 'a))  ; true true false
```
