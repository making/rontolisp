# fn

`(fn name? [params...] body...)`
`(fn name? ([params...] body...)+`

Builds a lambda. The optional name binds itself for self-calls (a `labels` self-binding,
so the recursive call is direct). Several arities are one `lambda` dispatching on the
argument count, the same way a multi-arity `defn` dispatches; clauses close over the
outer scope (there are no local functions). Parameters destructure, vector and map
patterns alike. A `recur` in the body jumps back to the enclosing clause with new
argument values.

The reader's `#(...)` form reads as the oracle's reader reads it, `(fn* [p1__N# ...]
(body...))`: `%` and `%1` are the first parameter, `%N` the Nth, `%&` the rest, and the
parameter vector runs up to the highest `%N` used, so a call with any other count
signals. The body forms wrap as ONE call -- multi-form bodies need an explicit `do`. A
`#(...)` inside another is refused. Quoted, read with `read-string` or passed to a macro,
it is that `fn*` form.

```clojure
(println ((fn [a b] (+ (* a 10) b)) 4 2)) ; 42
(println (map #(* % %) '(1 2 3)))         ; (1 4 9)
(println (#(+ %1 %2) 10 20))              ; 30
(println ((fn fact [n] (if (< n 2) 1 (* n (fact (- n 1))))) 5)) ; 120
(println ((fn countdown [n acc] (if (zero? n) acc (recur (dec n) (+ acc n)))) 5 0)) ; 15
```
