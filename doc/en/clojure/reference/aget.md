# aget

`(aget array index ...)`

Answers the element at the subscripts. One index per dimension is required; a wrong count or an index out of range signals.

```clojure
(def ag-a (make-array String 2))
(aset ag-a 0 "x")
(println (aget ag-a 0)) ; x
(println (aget ag-a 1)) ; nil
(def ag-m (make-array Long 2 3))
(aset ag-m 1 2 7)
(println (aget ag-m 1 2)) ; 7
```
