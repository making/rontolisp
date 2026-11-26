# remove-method

`(remove-method multifn dispatch-value)`

Drops the method stored under `dispatch-value` from the multimethod's table. Answers the
multimethod; a later call falls through the dispatch search again.

```clojure
(defmulti r :k)
(defmethod r :x [m] 1)
(remove-method r :x)
(println (if (get-method r :x) :yes :no)) ; no
```
