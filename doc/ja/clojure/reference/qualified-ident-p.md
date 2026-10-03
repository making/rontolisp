# qualified-ident?

`(qualified-ident? x)`

`clojure.core/qualified-ident?`: 名前空間のあるキーワードまたはシンボルなら `true` を返します。値としては1引数の関数です。

```clojure
(println (qualified-ident? :a/b) (qualified-ident? 'a))  ; true false
```
