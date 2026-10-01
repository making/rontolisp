# dosync

`(dosync body...)`

トランザクションを開いて本体を実行し、最後の値を返します。スレッドが1つなので、トランザクションは分離ではなく動的エクステントです。リトライも競合も起きません。外側での `alter`/`commute`/`ref-set`/`ensure` はシグナルを上げます（`No transaction running`）。

```clojure
(def r (ref 0))
(println (dosync (alter r inc) (alter r inc))) ; 2
(println @r) ; 2
```
