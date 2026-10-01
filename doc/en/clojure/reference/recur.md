# recur

`(recur expr...)`

Retargets the enclosing `loop`'s bindings to the given values and jumps back to its
body; the values are evaluated before the jump, in the bindings' order. It runs only
inside a `loop` -- there is no function-position `recur`, since the lowering is the
`loop`'s `labels` self call. Constant-stack by the interpreter's tail calls.

```clojure
(println (loop [i 0 acc 0]
           (if (= i 5) acc (recur (inc i) (+ acc i))))) ; 10
```
