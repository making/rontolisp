# ident?

`(ident? x)`

`clojure.core/ident?`: キーワードまたはシンボルなら `true` を返します。値としては1引数の関数です。

```clojure
(println (ident? :a) (ident? 'a) (ident? "a"))  ; true true false
```
