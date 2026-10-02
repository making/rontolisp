# make-array

`(make-array Class dim ...)`

Answers a general array over the dimensions. The class names the element type and is ignored: every array here is general, and an unwritten slot reads `nil`. One dimension is a vector of that length; several give a multi-dimensional array. The class must be a name, not an expression.

```clojure
(def mk-a (make-array String 3))
(println (alength mk-a))     ; 3
(println (aget mk-a 0))      ; nil
(def mk-b (make-array Long 2 3))
(println (alength mk-b))     ; 2
```
