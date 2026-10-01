# defmulti

`(defmulti name docstring? dispatch-fn :default default?)`

Defines a multimethod: a method table keyed by dispatch value plus a dispatcher. `dispatch-fn`
runs on every call and its answer picks the method; the optional `:default` names the fallback
dispatch value (`:default` itself without the option). `:hierarchy` takes a hierarchy value the
dispatch search walks instead of the global one, evaluated on every dispatch. The name lowers to
a rest-args function applying each call's dispatch value to the table.

A miss with no default method signals `No method in <name> for dispatch value: <value>`. The
dispatch search widens past exact hits through the hierarchy (see `defmethod`).

A `class` dispatch runs over the kind keywords `class` answers (`:string`, `:number`,
`:map`, ...), so host spellings name the same rows: `String`, `Number` (every numeric
spelling merges into `:number`, where the oracle tells `Long` from `Double`), dotted
names like `java.util.Map`, `clojure.lang.IPersistentVector`, and `nil`. An `Object`
method catches what the hierarchy search misses, ahead of the default.

```clojure
(defmulti area :shape)
(defmethod area :default [m] 0)
(println (area {:shape :x})) ; 0

(defmulti what "tags" (fn [x] (:t x)) :default :other)
(defmethod what :other [x] 99)
(println (what {:t :zzz})) ; 99

(defmulti printable class)
(defmethod printable String [s] (str "str:" s))
(defmethod printable nil [_] "was-nil")
(defmethod printable :default [x] "dflt")
(println (printable "a")) ; str:a
(println (printable nil)) ; was-nil
(println (printable :k)) ; dflt
```
