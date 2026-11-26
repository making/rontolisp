# aset

`(aset array index ... value)`

Stores `value` at the subscripts, mutating the array, and answers `value`. One index per dimension is required. A [byte array](byte-array.md) takes an integer from `-128` to `127`, what [byte](byte.md) answers; any other value is the oracle's `IllegalArgumentException`.

```clojure
(def as-a (make-array String 2))
(println (aset as-a 0 "x")) ; x
(println (aget as-a 0))     ; x
(def as-m (make-array Long 2 3))
(aset as-m 1 2 7)
(println (aget as-m 1 2))   ; 7
(println (aset (byte-array 1) 0 (byte -5))) ; -5
```
