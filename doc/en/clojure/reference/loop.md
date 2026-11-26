# loop

`(loop [binding...] body...)`

Binds like `let` -- the inits are sequential and the patterns destructure -- and
evaluates the body, which may `(recur ...)` back to the bindings with new values.
Lowers to a `labels` self call, so the interpreter's tail calls make the repetition
constant-stack.

```clojure
(println (loop [a 0 b 1 i 0]
           (if (= i 10) a (recur b (+ a b) (inc i))))) ; 55
(println (loop [i 0] (if (= i 3) i (recur (inc i)))))  ; 3
```

A closure the body creates captures its own iteration's value, like the oracle's --
each `recur` is a fresh call with fresh parameters:

```clojure
(println (let [fns (atom [])] (loop [i 0] (when (< i 3) (swap! fns conj (fn [] i)) (recur (inc i)))) (mapv (fn [f] (f)) @fns))) ; [0 1 2]
```
