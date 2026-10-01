# use

`(use clause)`

Loads a namespace and refers the names the clause lists, answering `nil` --
`(:only [...])` narrows them, the same refer wiring `ns` does. `clojure.string`
and `clojure.java.io` (`reader` only) resolve;
an unknown namespace is an error.

```clojure
(use [clojure.string :only [upper-case]])
(println (upper-case "hi")) ; HI
```
