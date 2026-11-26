# alength

`(alength array)`

Answers the size of the array's first dimension; for a multi-dimensional array, the outer length, for a [byte array](byte-array.md) its count of elements.

```clojure
(println (alength (make-array String 2)))   ; 2
(println (alength (make-array Long 4 5)))   ; 4
(println (alength (byte-array 3)))          ; 3
```
