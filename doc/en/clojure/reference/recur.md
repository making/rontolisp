# recur

`(recur expr...)`

Retargets the innermost enclosing `loop`, named or anonymous `fn`, `defn` or `letfn`
entry to the given values and jumps back to its body; the values are evaluated before the
jump, in the bindings' order. Each multi-arity clause is its own target, so the count
must match the enclosing clause; a wrong count is an error, and `recur` outside any
loop or function is one too. Only a `recur` in tail position lowers -- anywhere else
is an error, and a `try` between the `recur` and its target is one too. A `recur`
reaching a variadic (`[x & xs]`) clause assigns through a worker: the clause splits
into a worker taking the rest as an ordinary parameter plus the `&rest` head for
normal calls, so the last `recur` argument binds the rest itself, like the oracle
(an unused variadic keeps its single shape). A `lazy-seq` body is its own zero-arity
target: a `recur` in its tail position re-runs the thunk itself, so a recur with
arguments is an arity error there. A `deftype`, `defrecord` or `reify` method
recurs to its own arity with every parameter but the target, which the method
supplies itself, like the oracle; an `extend-protocol` or `extend-type` body is a `fn`,
whose `recur` passes the target too. Constant-stack by the interpreter's
tail calls.

```clojure
(println (loop [i 0 acc 0]
           (if (= i 5) acc (recur (inc i) (+ acc i))))) ; 10
(println ((fn countdown [n acc] (if (zero? n) acc (recur (dec n) (+ acc n)))) 5 0)) ; 15
(defn greet-again [n]
  (if (zero? n) :done (recur (dec n))))
(println (greet-again 3)) ; :done
(defn walk [a & r]
  (if (empty? r) a (recur (first r) (rest r))))
(println (walk :start [1])) ; [1]
(println (walk :start nil)) ; nil
(defmulti walk-shape (fn [m & r] (:shape m)))
(defmethod walk-shape :go [m & r]
  (if (empty? r) (:v m) (recur {:v (first r)} (rest r))))
(println (walk-shape {:shape :go :v :start} [1])) ; [1]
(defprotocol Counter (count-up [c acc]))
(deftype Limit [n] Counter
  (count-up [this acc] (if (>= acc n) acc (recur (inc acc)))))
(println (count-up (Limit. 3) 0)) ; 3
(def calls (atom 0))
(def draining (lazy-seq (swap! calls inc) (when (< @calls 3) (recur))))
(println (first draining)) ; nil
(println @calls) ; 3
```
