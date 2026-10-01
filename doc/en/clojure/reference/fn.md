# fn

`(fn name? [params...] body...)`
`(fn name? ([params...] body...)+`

Builds a lambda. The optional name binds itself for self-calls (a `labels` self-binding,
so the recursive call is direct). Several arities are one `lambda` dispatching on the
argument count, the same way a multi-arity `defn` dispatches; clauses close over the
outer scope (there are no local functions). Parameters destructure, vector and map
patterns alike.

The reader's `#(...)` form is the same lambda with the arguments traveling as one rest
list: `%` is the first, `%N` the Nth (at most 9), `%&` the rest, and the body forms wrap
as ONE call -- multi-form bodies need an explicit `do`.

```clojure
(println ((fn [a b] (+ (* a 10) b)) 4 2)) ; 42
(println (map #(* % %) '(1 2 3)))         ; (1 4 9)
(println (#(+ %1 %2) 10 20))              ; 30
(println ((fn fact [n] (if (< n 2) 1 (* n (fact (- n 1))))) 5)) ; 120
```
