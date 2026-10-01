# defmulti

`(defmulti name docstring? dispatch-fn :default default?)`

Defines a multimethod: a method table keyed by dispatch value plus a dispatcher. `dispatch-fn`
runs on every call and its answer picks the method; the optional `:default` names the fallback
dispatch value (`:default` itself without the option). `:hierarchy` takes a hierarchy value the
dispatch search walks instead of the global one, evaluated on every dispatch. The name lowers to
a rest-args function applying each call's dispatch value to the table.

A miss with no default method signals `No method in <name> for dispatch value: <value>`. The
dispatch search widens past exact hits through the hierarchy (see `defmethod`).

```clojure
(defmulti area :shape)
(defmethod area :default [m] 0)
(println (area {:shape :x})) ; 0

(defmulti what "tags" (fn [x] (:t x)) :default :other)
(defmethod what :other [x] 99)
(println (what {:t :zzz})) ; 99
```
