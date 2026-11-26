# seq?

`(seq? x)`

`clojure.core/seq?`: リストまたは lazy seq なら `true`、ベクター・マップ・セット・文字列・`nil` なら `false` を返します。ここでは `nil` が空リストなので、オラクルが `true` を返す `(seq? ())` は `false` です。strict な入力に対して操作が返す seq はリストです。値としては1引数の関数です。

```clojure
(println (seq? '(1 2)) (seq? (map inc [1])))  ; true true
(println (seq? [1 2]) (seq? nil))           ; false false
```
