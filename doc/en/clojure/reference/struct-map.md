# struct-map

`(struct-map struct-map key value...)`

A fresh map with the struct's keys (`nil` unless overridden here), like the
oracle's three-argument constructor over named slots.

```clojure
(defstruct account :id :balance)
(println (:balance (struct-map account :id 9))) ; nil
(println (:id (struct-map account :id 9))) ; 9
```
