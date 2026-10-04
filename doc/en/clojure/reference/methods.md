# methods

`(methods multifn)`

Answers the multimethod's method table as a map from dispatch value to method lambda. The map is
a copy: a later `defmethod` or `remove-method` does not reach it. The `nil` row is keyed by `nil`,
and the `Object` and default rows are in it. A host class's row is under the keyword `class`
answers for the class (the oracle keys it by the `Class` itself). The argument is the
multimethod's name, like `get-method`'s.

```clojure
(defmulti f :t)
(defmethod f :a [x] 1)
(defmethod f :b [x] 2)
(println (sort (keys (methods f)))) ; (:a :b)
(println ((get (methods f) :b) {})) ; 2
```
