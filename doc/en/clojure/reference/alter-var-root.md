# alter-var-root

`(alter-var-root v f & args)`

Replaces the root of the var `v` with `(apply f root args)` and answers the new root.
A `defn` named as `#'f` by an `alter-var-root` is called through its var, so every
caller sees the new root, like a `with-redefs` target. In a program that alters a var
it computes (a local, `alter-var-root` as a value), every `defn` whose var it takes
with `#'` is called through its var.

A `clojure.core` var, a multimethod, a protocol method, a record constructor and a
test have no root to replace and are refused by name, as is a `defn` an earlier REPL
input defined without `^:redef`. A value that is no var is a `ClassCastException`,
`nil` a `NullPointerException`.

Deviation: the root is the var's value cell, so inside a `binding` of a `^:dynamic`
var, `alter-var-root` of that var changes the binding instead of the root.

```clojure
(def counter 1)
(println (alter-var-root #'counter + 10) counter) ; 11 11
(defn greet [] "hello")
(defn welcome [] (greet))
(alter-var-root #'greet (fn [old] (fn [] (str (old) "!"))))
(println (welcome)) ; hello!
```
