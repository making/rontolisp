# Throwable->map

`(Throwable->map ex)`

例外をオラクル同様にデータとして返します。`:via` は cause の連鎖をたどった例外ごとのマップ
（`:type` はクラスのシンボル、`:message` と `:data` はあるときだけ）、`:cause` と `:data` は
根本の cause のメッセージとデータ、`:phase` は `ex` 自身のデータの `:clojure.error/phase`、
`:trace` は根本のスタックフレームです。ここの例外はフレームを持たないので、`:trace` は `[]` で、
`:via` のマップに `:at` はありません。オラクルが空のスタックトレースに返すものと同じです。
`ex-info`、throwable の構築、実行時エラー、インタプリタと JVM ではホストの例外に使えます。
`nil` はオラクルの `NullPointerException`、それ以外の値は `ClassCastException` です。関数値と
しても動きます。[clojure.datafy](clojure-datafy.md) は例外についてこのマップを返します。

```clojure
(def m (Throwable->map (ex-info "outer" {:id 1} (Exception. "root"))))
(println (map :type (:via m))) ; (clojure.lang.ExceptionInfo java.lang.Exception)
(println (:cause m) (:trace m)) ; root []
(println (:data (first (:via m)))) ; {:id 1}
```
