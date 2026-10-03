# conj

`(conj coll x ...)`

Answers a fresh collection with the values added, per kind: a member onto a set, an entry
onto a map, at the end of a vector, at the front of a list -- `nil` collects like a list.
A set conjoined onto a map contributes its members as entries (two-member vectors); a list,
including a `(k v)` one, is no entry and signals, like anything else conjoined onto a map. Entries conjoined onto a record keep the type; onto an atom (or a ref/agent/volatile, the same cell), a deftype or reify it signals, like the oracle.

As a value a collection plus a rest list of items, folded one by one -- so `alter` and
`swap!` over `conj` run what a call would run. With no arguments `[]`, the init arity
`transduce` calls.

Deviation: transients (`conj!`) are refused by name.

```clojure
(println (conj [1 2] 3))  ; [1 2 3]
(println (conj '(1 2) 3)) ; (3 1 2)
(println (conj nil 1))    ; (1)
(println (get (conj {:a 1} [:b 2]) :b)) ; 2
(println (count (conj #{1 2} 3)))       ; 3
(println (map conj [[1] [2]] [3]))     ; ([1 3])
(println (conj)) ; []
```
