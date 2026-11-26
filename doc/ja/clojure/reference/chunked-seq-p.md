# chunked-seq?

`(chunked-seq? x)`

`clojure.core/chunked-seq?`: どの値にも `false` を返します。ここでは seq が1要素ずつ実体化され、チャンク化された seq はありません（オラクルは `(seq [1 2])` や `range` に `true` を返します）。引数は評価されます。値としては1引数の関数です。

```clojure
(println (chunked-seq? (seq [1 2])))  ; false
```
