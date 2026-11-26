# struct-map

`(struct-map struct-map key value...)`

A fresh map with the struct's keys (`nil` unless overridden here), like the
oracle's three-argument constructor over named slots.

```clojure
(defstruct ticket :seat :price)
(println (:price (struct-map ticket :seat "B7"))) ; nil
(println (:seat (struct-map ticket :seat "B7"))) ; B7
```
