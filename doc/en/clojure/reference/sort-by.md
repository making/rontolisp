# sort-by

`(sort-by keyfn coll)` / `(sort-by keyfn comp coll)`

Like `sort`, comparing the keyed values instead of the members. As a value the
key function, then one or two more arguments.

```clojure
(println (sort-by count ["aaa" "b" "cc"])) ; (b cc aaa)
```
