# dosync

`(dosync body...)`

本体を1つのトランザクションとして実行し、最後の値を返します。読み出しは記録され、コミット時点で他のトランザクションが読んだ ref に書き込んでいれば本体をリトライします。そのため本体は複数回走ることがあり、副作用も繰り返すことがあります（オラクル同様）。書き込みはコミット時に初めて着地するため、並行する本体が未コミットの書き込みを読むことはありません（入れ子の `dosync` は実行中のものに合流します）。本体が戻ると、書き込んだ各 ref の validator が最終値に対して走ります。本体がシグナルを上げると、書き込んだ ref はすべて元の値に戻り、本体の中の送信は捨てられます。外側での `alter`/`commute`/`ref-set`/`ensure` はシグナルを上げます（`No transaction running`）。

```clojure
(def r (ref 0))
(println (dosync (alter r inc) (alter r inc))) ; 2
(try (dosync (alter r inc) (throw (Exception. "undo"))) (catch Exception e nil))
(println @r) ; 2
```
