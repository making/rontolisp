# alter-meta!

`(alter-meta! ref f & args)`

Replaces the metadata of the reference `ref` -- a var, an atom, a ref, an agent or a
namespace -- with `(apply f meta args)` and answers it. A var keeps the new metadata
until a later definition of its name replaces it, like the oracle's `def`. Any other
value (a volatile, a collection) and a result that is no map are a
`ClassCastException`, `nil` a `NullPointerException`. As a value a function of two or
more arguments.

```clojure
(defn add "Adds two numbers." [a b] (+ a b))
(def plus add)
(alter-meta! #'plus assoc :doc (:doc (meta #'add)))
(println (:doc (meta #'plus))) ; Adds two numbers.
```
