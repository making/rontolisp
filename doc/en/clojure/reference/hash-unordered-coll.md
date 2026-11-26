# hash-unordered-coll

`(hash-unordered-coll coll)`

`clojure.core/hash-unordered-coll`: the hash `=` keeps for an unordered collection, computed
over any `Iterable`: the sum of its members' `hash`, mixed with the count, so it equals `hash`
of a set of the same members (of a map when the members are its entries). A type implementing
a map or set interface answers its own `hasheq` through it. It refuses like
[hash-ordered-coll](hash-ordered-coll.md).

```clojure
(prn (hash-unordered-coll [1 2]))                     ; 460223544
(prn (= (hash-unordered-coll [2 1]) (hash #{1 2})))   ; true
```
