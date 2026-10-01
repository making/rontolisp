# set

`(set coll)`

Answers the set of the elements of `coll`: a list, vector, map (its entry vectors) or set
dedupes into a fresh `equal` table with each member stored under itself; `set` of `nil`
is the empty set. Members compare like `equal` table keys, so vectors dedupe by identity.

As a value a one-argument lambda.

Deviation: a repeated set-literal element is refused by spelling at read time, but
equal members arriving at run time dedupe silently.

```clojure
(println (count (set [1 2 2 3])))     ; 3
(println (contains? (set [1 2 2 3]) 2)) ; true
(println (count (set nil)))           ; 0
(println (map set [[1]]))             ; (#{1})
```
