# struct

`(struct struct-map value...)`

A fresh map pairing the struct's keys with the values; missing values are
`nil`, too many signal. The map is an ordinary map afterwards: keywords read
it, verbs rebuild it.

```clojure
(defstruct account :id :balance)
(println (:balance (struct account 1))) ; nil
(println (:id (struct account 1 100))) ; 1
```
