# prefers

`(prefers multifn)`

Each dispatch value with the set preferred over it, like the oracle's map
(`{}` where nothing is preferred). The multimethod's name, like `methods`: a
local bound to one is refused at lowering, and there is no function value.

```clojure
(defmulti serve :kind)
(defmethod serve :coffee [m] :hot)
(prefer-method serve :coffee :tea)
(println (= (prefers serve) {:coffee #{:tea}})) ; true
```
