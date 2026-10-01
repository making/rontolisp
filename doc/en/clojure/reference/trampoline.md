# trampoline

`(trampoline f args...)`

Answers `f` applied, then every thunk result (a zero-argument function) invoked
with no arguments until a non-function answers -- a self-call loop, so mutual
thunk chains stay constant-stack. As a value the function, then any arguments.

```clojure
(defn b15-blast [x] (if (zero? x) :done (fn [] (b15-blast (dec x)))))
(println (trampoline b15-blast 50)) ; :done
```
