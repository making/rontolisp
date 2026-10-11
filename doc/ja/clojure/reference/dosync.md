# dosync

`(dosync body...)`

本体を1つのトランザクションとして実行し、最後の値を返します。トランザクションは1つのロックの下で1つずつ走るため（入れ子の `dosync` は実行中のものに合流します）、リトライは起きず、本体の副作用は1回だけ走ります。本体が戻ると、書き込んだ各 ref の validator が最終値に対して走ります。本体がシグナルを上げると、書き込んだ ref はすべて元の値に戻り、本体の中の送信は捨てられます。外側での `alter`/`commute`/`ref-set`/`ensure` はシグナルを上げます（`No transaction running`）。

```clojure
(def r (ref 0))
(println (dosync (alter r inc) (alter r inc))) ; 2
(try (dosync (alter r inc) (throw (Exception. "undo"))) (catch Exception e nil))
(println @r) ; 2
```
