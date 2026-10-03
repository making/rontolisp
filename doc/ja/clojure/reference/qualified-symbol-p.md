# qualified-symbol?

`(qualified-symbol? x)`

`clojure.core/qualified-symbol?`: 名前空間のあるシンボルなら `true` を返します。値としては1引数の関数です。

```clojure
(println (qualified-symbol? 'a/b) (qualified-symbol? 'a))  ; true false
```
