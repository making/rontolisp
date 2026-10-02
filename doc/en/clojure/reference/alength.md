# alength

`(alength array)`

Answers the size of the array's first dimension; for a multi-dimensional array, the outer length.

```clojure
(println (alength (make-array String 2)))   ; 2
(println (alength (make-array Long 4 5)))   ; 4
```
