# dosync

`(dosync body...)`

Runs the body as one transaction and answers its last value. Reads are tracked,
and at the commit point the body retries when another transaction committed a
write to a read ref -- so a body may run more than once and its side effects may
repeat, like the oracle's. Writes only land at commit, so a concurrent body
never reads an uncommitted write (a nested `dosync` joins the running one). When
the body returns, the validator of every ref it wrote runs on the final value;
when it signals, every ref it wrote gets its old value back and the sends it
made are dropped. `alter`/`commute`/`ref-set`/`ensure`
outside one signal (`No transaction running`).

```clojure
(def r (ref 0))
(println (dosync (alter r inc) (alter r inc))) ; 2
(try (dosync (alter r inc) (throw (Exception. "undo"))) (catch Exception e nil))
(println @r) ; 2
```
