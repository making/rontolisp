# val

`(val e)`

`clojure.core/val`: マップエントリ `e` の値を返します。何がエントリで何がシグナルになるかは
`key` と同じです。値としては1引数の関数です。

```clojure
(println (val (first {:a 1})))     ; 1
(println (map val {:a 1 :b 2}))    ; (1 2)
```
