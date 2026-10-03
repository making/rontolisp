# hash-set

`(hash-set x ...)`

`clojure.core/hash-set`: a set of the arguments, a repeated one kept once. The same equal
hash table as a `#{...}` literal, so iteration order is unspecified. As a value a function
of any number of arguments.

```clojure
(prn (count (hash-set 1 2 1))) ; 2
(prn (hash-set))               ; #{}
```
