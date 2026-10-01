# recur

`(recur expr...)`

Retargets the innermost enclosing `loop`, named or anonymous `fn`, `defn` or `letfn`
entry to the given values and jumps back to its body; the values are evaluated before the
jump, in the bindings' order. Each multi-arity clause is its own target, so the count
must match the enclosing clause; a wrong count is an error, and `recur` outside any
loop or function is one too. Only a `recur` in tail position lowers -- anywhere else
is an error, and a `try` between the `recur` and its target is one too. A `recur`
reaching a variadic (`[x & xs]`) clause is
refused: the oracle binds the rest parameter to the last argument itself, which no
`&rest` self call spells. Constant-stack by the interpreter's tail calls.

```clojure
(println (loop [i 0 acc 0]
           (if (= i 5) acc (recur (inc i) (+ acc i))))) ; 10
(println ((fn countdown [n acc] (if (zero? n) acc (recur (dec n) (+ acc n)))) 5 0)) ; 15
(defn greet-again [n]
  (if (zero? n) :done (recur (dec n))))
(println (greet-again 3)) ; :done
```
