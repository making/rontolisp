# trampoline

`(trampoline f args...)`

Answers `f` applied, then every thunk result (a zero-argument function) invoked
with no arguments until a non-function answers -- a self-call loop, so mutual
thunk chains stay constant-stack. As a value the function, then any arguments.

```clojure
(defn blast [x] (if (zero? x) :done (fn [] (blast (dec x)))))
(println (trampoline blast 50)) ; :done
```
