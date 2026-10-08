# with-redefs

`(with-redefs [var value ...] body...)`

Evaluates every value in order, replaces each var's root for the body, and restores
the old roots when the body exits, also when it throws. The replaced root reaches every
caller: a `defn` that a `with-redefs` names anywhere in the program, or one marked
`^:redef`, is called through its var instead of directly. A later binding of the same
var wins. A function value taken before the body keeps the old function.

Only a var a `def`, `defn` or `declare` made can be redefined. A local and an unknown
name are refused as `Unable to resolve var`. A `clojure.core` var, a macro, a
multimethod, a protocol method, a record constructor and a test are refused by name.
In the REPL, a `defn` an earlier input defined without `^:redef` is refused, because
its calls were already lowered as direct calls.

Deviation: the root is the var's value cell, so inside a `binding` of a `^:dynamic`
var, `with-redefs` of that var changes the binding instead of the root.

```clojure
(defn fetch [id] (str "real-" id))
(defn report [id] (str "report of " (fetch id)))
(println (with-redefs [fetch (fn [id] (str "stub-" id))] (report 7))) ; report of stub-7
(println (report 7))                                                    ; report of real-7
```
