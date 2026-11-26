# hash-ordered-coll

`(hash-ordered-coll coll)`

`clojure.core/hash-ordered-coll`: the hash `=` keeps for an ordered collection, computed over
any `Iterable` (a vector, list, seq, map, set, record, sorted collection, a type implementing
`Iterable`, a Java collection): from 1, 31 times the running hash plus each member's `hash`,
mixed with the count, so it equals `hash` of a vector of the same members. A map's members
are its entries. `nil` is the oracle's `NullPointerException` and a value that is no
`Iterable` (a string, a number, a keyword) its `ClassCastException`.

```clojure
(prn (hash-ordered-coll [1 2]))                     ; 156247261
(prn (= (hash-ordered-coll '(1 2)) (hash [1 2])))   ; true
```
