# find-var

`(find-var sym)`

Answers the var a namespace-qualified symbol names, or `nil` when the namespace has no
such var, like the oracle's `Var.find`: the namespace part is a namespace's own name,
never an alias, and one that does not exist is the oracle's `IllegalArgumentException`
(`No such namespace: x`), as is an unqualified symbol (`Symbol must be
namespace-qualified`). A quoted symbol resolves while the program lowers; a computed one
resolves only in a macro body (hiccup's `html` finds its dynamic vars this way) and
throws an `UnsupportedOperationException` at run time. Also names a function value.

```clojure
(def ^:dynamic *depth* 0)
(println (find-var 'user/*depth*)) ; #'user/*depth*
(println (find-var 'user/nowhere)) ; nil
(println (find-var 'clojure.string/join)) ; #'clojure.string/join
```
