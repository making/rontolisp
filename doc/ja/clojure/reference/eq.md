# =

`(= x...)`

連鎖の隣接要素を比較します。隣接するすべてのペアが等しければ `true`、引数 1 つなら常に `true` です。マップはエントリ単位で、セットは要素単位で、いずれも深く構造的に比較します。ベクター・リスト・lazy seq は種類をまたいで要素ごとに比較します。比較は truthiness ではなく `equal` 型です。2 つのオブジェクトが別物なので `(= false nil)` は `false` です。同じアリティの関数値として動きます。

```clojure
(println (= 1 1 1)) ; true
(println (= 1 1 2)) ; false
(println (= {:a [1 2]} {:a [1 2]})) ; true
(println (= [1 2] '(1 2))) ; true
(println (= false nil)) ; false
```
