# merge-with

`(merge-with f maps...)`

Answers one fresh map over every map's pairs, conflicts resolved through `f`
over the old and new values. `nil` maps contribute nothing, and a Java `Map` its
entries (interpreter and JVM); `(merge-with f)` is `nil`. The result keeps a record's type only when the first map is
one, like the oracle: a `nil` first map answers a plain map even when a later
map is a record. As a value the function, then any number of maps, with the
same record rewrap.

```clojure
(println (merge-with + {:a 1} {:a 2})) ; {:a 3}
```
