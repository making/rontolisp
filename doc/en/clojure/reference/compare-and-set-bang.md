# compare-and-set!

`(compare-and-set! atom expected v)`

When the atom's value compares equal to `expected`, stores `v`; answers `true` or `false`
either way, the new value read back through `deref`. The comparison is `eql`: numbers by
value, everything else by identity.

```clojure
(def a (atom 2))
(println (compare-and-set! a 2 3)) ; true
(println (compare-and-set! a 2 9)) ; false
(println @a) ; 3
```
