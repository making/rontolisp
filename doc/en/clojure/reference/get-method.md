# get-method

`(get-method multifn dispatch-value)`

Answers the method lambda stored under `dispatch-value`, or `nil` when none is. The lambda is a
first-class function value, callable with the multimethod's parameters.

```clojure
(defmulti g :k)
(defmethod g :x [m] 1)
(println ((get-method g :x) {:k :x})) ; 1
(println (get-method g :zzz)) ; nil
```
