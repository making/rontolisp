# remove-all-methods

`(remove-all-methods multifn)`

Every method and preference gone, answering the multimethod, like the
oracle's. The multimethod's name, like `methods`: a local bound to one is
refused at lowering, and there is no function value.

```clojure
(defmulti serve :kind)
(defmethod serve :coffee [m] :hot)
(remove-all-methods serve)
(println (methods serve)) ; {}
```
