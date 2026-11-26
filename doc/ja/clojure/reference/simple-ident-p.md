# simple-ident?

`(simple-ident? x)`

`clojure.core/simple-ident?`: 名前空間のないキーワードまたはシンボルなら `true` を返します。値としては1引数の関数です。

```clojure
(println (simple-ident? :a) (simple-ident? 'a/b))  ; true false
```
