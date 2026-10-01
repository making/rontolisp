# require

`(require clause)`

Loads a namespace and wires the clause's `:as` alias and `:refer`ed names, answering `nil` --
the same wiring `ns` does, spelled at top level with the vector clause form. Only
`clojure.string` resolves; an unknown namespace is an error.

```clojure
(require [clojure.string :as s])
(println (s/join "," ["a"])) ; a
```
