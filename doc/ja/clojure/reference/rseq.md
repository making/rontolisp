# rseq

`(rseq v)`

`clojure.core/rseq`: ベクター `v` の要素を末尾から順にリストで返します。`v` が空なら `nil` です。
ベクター以外（`nil`、リスト、seq、文字列、マップ）は、オラクルと同様にシグナルを上げます。
値としては1引数の関数です。

```clojure
(println (rseq [1 2 3]))  ; (3 2 1)
(println (rseq []))       ; nil
```
