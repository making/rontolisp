# any?

`(any? x)`

`clojure.core/any?`: `nil` と `false` を含め、どの値にも `true` を返します。値としては1引数の関数です。

```clojure
(println (any? nil) (any? false))  ; true true
```
