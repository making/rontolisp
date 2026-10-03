# simple-keyword?

`(simple-keyword? x)`

`clojure.core/simple-keyword?`: 名前空間のないキーワードなら `true` を返します。値としては1引数の関数です。

```clojure
(println (simple-keyword? :a) (simple-keyword? :a/b) (simple-keyword? 'a))  ; true false false
```
