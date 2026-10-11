# dosync

`(dosync body...)`

Runs the body as one transaction and answers its last value. Transactions run one at
a time under one lock (a nested `dosync` joins the running one), so nothing retries
and the body's side effects run once. When the body returns, the validator of every
ref it wrote runs on the final value; when it signals, every ref it wrote gets its old
value back and the sends it made are dropped. `alter`/`commute`/`ref-set`/`ensure`
outside one signal (`No transaction running`).

```clojure
(def r (ref 0))
(println (dosync (alter r inc) (alter r inc))) ; 2
(try (dosync (alter r inc) (throw (Exception. "undo"))) (catch Exception e nil))
(println @r) ; 2
```
