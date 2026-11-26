# get-method

`(get-method multifn dispatch-value)`

Answers the method a call dispatching to `dispatch-value` runs: the method stored under it, else
the one an ancestor in the multimethod's hierarchy holds, else the default's, else `nil`. The
method is a first-class function value, callable with the multimethod's parameters.

```clojure
(defmulti g :k)
(defmethod g :x [m] 1)
(println ((get-method g :x) {:k :x})) ; 1
(println (get-method g :zzz)) ; nil
(defmethod g :default [m] 0)
(println ((get-method g :zzz) {})) ; 0
```
