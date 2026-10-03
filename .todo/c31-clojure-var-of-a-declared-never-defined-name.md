# c31. Clojure: `#'x` of a declared, never-defined name does not compile

Difficulty: Medium

`(declare y) (def v #'y) (println @v)` (2026-10-03): the interpreter signals `The function c%y is
undefined`, the JVM and both wasm targets refuse to compile (`Cannot compile: c%y`). The oracle (`clj`
1.12.6) builds the var and its deref answers the unbound marker (`#object[clojure.lang.Var$Unbound ...
Unbound: #'user/y]`); `(bound? #'y)` is `false` there.

The var's getter reads the name as a function (the pre-scan registers a `declare`d name as one). An unbound
var needs a representation: a getter that answers an unbound marker, plus `bound?` reading it
(`%clojure-is-bound` answers true for every var today). Pin `bound?` and the deref on all four backends.
