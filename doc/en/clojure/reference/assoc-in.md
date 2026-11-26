# assoc-in

`(assoc-in m keys v)`

Answers the nested association down the key path, building missing levels
from empty. The path is any seqable; with no keys, associates under `nil`, like the
oracle. A vector level steps by index, like [assoc](assoc.md): the index equal to the
count appends a new level, so `(assoc-in [[0 0]] [1 0] :x)` is `[[0 0] {0 :x}]`.

```clojure
(println (assoc-in {} [:a :b] 1)) ; {:a {:b 1}}
(println (assoc-in [[0 0] [0 0]] [1 0] :x)) ; [[0 0] [:x 0]]
```
