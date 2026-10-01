# require

`(require clause)`

Loads a namespace and wires the clause's `:as` alias and `:refer`ed names, answering `nil` --
the same wiring `ns` does, spelled at top level with the vector clause form. `clojure.string`
and `clojure.java.io` (`reader` only) resolve; an unknown namespace is an error.

```clojure
(require [clojure.string :as s])
(println (s/join "," ["a"])) ; a
```
