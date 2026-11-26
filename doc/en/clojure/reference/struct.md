# struct

`(struct struct-map value...)`

A fresh map pairing the struct's keys with the values; missing values are
`nil`, too many signal. The map is an ordinary map afterwards: keywords read
it, verbs rebuild it.

```clojure
(defstruct point3 :x :y :z)
(println (:z (struct point3 1 2))) ; nil
(println (:y (struct point3 1 2 3))) ; 2
```
