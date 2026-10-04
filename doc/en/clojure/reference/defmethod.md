# defmethod

`(defmethod name dispatch-value [params...] body...)`

Stores a method lambda in the multimethod's table under `dispatch-value`; the parameters
destructure, map patterns included. On a call, an exact hit against the dispatch value applies
directly. Otherwise the dispatcher searches every method the value descends from through the
multimethod's hierarchy: the strictly most specific wins, `prefer-method` breaks the remaining
ties, and an unbroken tie signals `Multiple methods ...`.

Dispatch values compare like map keys, by `=`, so a vector dispatch value hits its method
directly. A dispatch value
may name a host class (`String`, `Number`, `java.util.Map`,
`clojure.lang.IPersistentVector`, ...) and stores under the keyword `class` answers for it,
so `class` multis dispatch to it. A throwable class (`IllegalArgumentException`,
`clojure.lang.ExceptionInfo`) or a stream class (`java.io.StringWriter`, `java.io.Writer`,
`java.io.Reader`) stores under its name as a keyword, the one `class` answers for an exception
or a stream, and the search follows the superclass chain like the oracle's Java inheritance:
a `NumberFormatException` reaches an `IllegalArgumentException` method ahead of an
`Exception` one. A true nil maps onto the `(:C%NIL)` marker
(no table ever keys on nil), so a literal `:nil` dispatch value answers only a `:nil`
method, like the oracle, and
`Object` matches every value past the search but ahead of the default.

Deviation: every numeric class spelling merges into `:number`, where the oracle tells `Long`
from `Double`.

```clojure
(defmulti m :shape)
(defmethod m :circle [x] 1)
(defmethod m :square [x] 2)
(println (m {:shape :circle})) ; 1

(defmulti m2 class)
(defmethod m2 String [s] (str "str:" s))
(defmethod m2 Number [n] (str "num:" n))
(println (m2 "a")) ; str:a
(println (m2 1)) ; num:1

(defmulti m3 class)
(defmethod m3 IllegalArgumentException [e] :iae)
(defmethod m3 Exception [e] :exception)
(println (m3 (NumberFormatException. "x")) (m3 (ex-info "m" {}))) ; :iae :exception
```
