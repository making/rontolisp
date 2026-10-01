# conj

`(conj coll x ...)`

Answers a fresh collection with the values added, per kind: a member onto a set, an entry
onto a map, at the end of a vector, at the front of a list -- `nil` collects like a list.
A set conjoined onto a map contributes its members one level deep (as entries); anything
else conjoined onto a map signals. Entries conjoined onto a record keep the type; onto a deftype or reify it signals, like the oracle.

Deviation: transients (`conj!`) are refused by name.

```clojure
(println (conj [1 2] 3))  ; [1 2 3]
(println (conj '(1 2) 3)) ; (3 1 2)
(println (conj nil 1))    ; (1)
(println (get (conj {:a 1} [:b 2]) :b)) ; 2
(println (count (conj #{1 2} 3)))       ; 3
```
