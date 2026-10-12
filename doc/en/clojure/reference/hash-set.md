# hash-set

`(hash-set x ...)`

`clojure.core/hash-set`: a set of the arguments, a repeated one kept once. The same equal
hash table as a `#{...}` literal: it prints its members, and `seq` walks them, in insertion
order, where the oracle's hash set has an order of its own. As a value a function
of any number of arguments.

```clojure
(prn (count (hash-set 1 2 1))) ; 2
(prn (hash-set))               ; #{}
```
