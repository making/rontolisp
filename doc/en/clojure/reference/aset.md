# aset

`(aset array index ... value)`

Stores `value` at the subscripts, mutating the array, and answers `value`. One index per dimension is required.

```clojure
(def as-a (make-array String 2))
(println (aset as-a 0 "x")) ; x
(println (aget as-a 0))     ; x
(def as-m (make-array Long 2 3))
(aset as-m 1 2 7)
(println (aget as-m 1 2))   ; 7
```
