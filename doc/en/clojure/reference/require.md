# require

`(require 'clause ...)`

Loads namespaces and wires each clause's `:as` alias and `:refer`ed names, answering `nil` --
the same wiring `ns` does, spelled at top level with quoted libspecs. `clojure.string`, `clojure.java.io` (`reader` only) and `clojure.test` resolve; an unknown namespace is an error.
An unquoted vector spec is accepted too, though real Clojure rejects it.
A prefix list `'(prefix [sub ...])` wires each member under the prefix, quoted or bare.

```clojure
(require '[clojure.string :as s])
(println (s/join "," ["a"])) ; a
```
