# amap

`(amap array index result expr)`

Maps `expr` across `array` by index: evaluates the array once, clones it, sets each
element of the clone to the value `expr` answers, and answers the clone. `index` names
the loop's index and `result` the clone; both stay local to the form. An
[array](int-array.md) here is a vector, so the answer prints as one.

```clojure
(def am-a (int-array [1 2 3]))
(println (vec (amap am-a i r (* 2 (aget r i))))) ; [2 4 6]
(println (vec am-a)) ; [1 2 3]
```
