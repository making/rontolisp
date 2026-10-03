# seqable?

`(seqable? x)`

`clojure.core/seqable?`: `seq` が受け取る値なら `true` を返します。`nil`、文字列、コレクション（リスト・lazy seq・ベクター・マップ・セット・レコード。ソート済みのものも含む）が該当します。値としては1引数の関数です。

```clojure
(println (seqable? nil) (seqable? "ab") (seqable? 1) (seqable? :a))  ; true true false false
```
