# c83. Clojure: `methods` is an unknown name

Difficulty: Low

All backends, measured 2026-10-04 against clj 1.12.6:

```clojure
(defmulti f :t)
(defmethod f :a [x] 1)
(defmethod f :b [x] 2)
(println (sort (keys (methods f))))   ; oracle (:a :b); here "unknown name: methods"
```

A multimethod's table is the `%methods` global (`ClojureDispatchLowering.tableGlobal`), an
`equal` hash table keyed by the lowered dispatch values (the nil marker `(:C%NIL)` for nil,
a class object for a host class). `methods` answers it as a map: the nil marker back to
nil, the `Object` row and the default included like the oracle's.
