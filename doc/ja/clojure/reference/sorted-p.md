# sorted?

`(sorted? x)`

`clojure.core/sorted?`: ソート済みのマップかセット（`sorted-map`・`sorted-set` とその `-by` 形）なら `true`、ハッシュマップ・セット・ベクターは `false` を返します。値としては1引数の関数です。

```clojure
(println (sorted? (sorted-map :a 1)) (sorted? {:a 1}) (sorted? [1 2]))  ; true false false
```
