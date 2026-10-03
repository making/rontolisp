# find

`(find coll key)`

`clojure.core/find`: the entry `[key value]` (a two-element vector) `coll` holds under
`key`, or `nil` when it holds none. A map or record answers the key it stores (keys compare
by `=`, so a vector key finds an `=` list key); a vector takes an integer index in range
and answers `[index member]`; `nil` is `nil`. Any other `coll` (a set, a string, a list)
signals. As a value a two-argument function.

```clojure
(println (find {:a 1} :a))    ; [:a 1]
(println (find {:a 1} :b))    ; nil
(println (find {:a nil} :a))  ; [:a nil]
(println (find [10 20] 1))    ; [1 20]
```
