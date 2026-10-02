# ns

`(ns name clauses...)`

Switches to the namespace, creating it, and wires its clauses: `:as` registers an alias,
`:refer`/`:use` unqualified names, `:import` class names for interop, and
`(:refer-clojure :only ...)`/`(:refer-clojure :exclude ...)` narrow the visible core. There
is no `:rename`. The definitions below the form belong to the namespace. A required
namespace other than `clojure.string`, `clojure.java.io` (`reader` only) and `clojure.test`
is a project namespace: one an earlier `ns` form of the program declared, or one whose
file on the source path loads when the clause runs -- once per program, again under
`:reload` ([Semantics](../semantics.md#namespaces-and-files)); a file
no root holds is an error.

```clojure
(ns demo (:require [clojure.string :as s :refer [join]]))
(println (s/upper-case "hi")) ; HI
(println (join "-" ["a" "b"])) ; a-b
```

```clojure
(ns geo.shapes)
(defn area [w h] (* w h))
(ns geo.main (:require [geo.shapes :as s]))
(println (s/area 2 3)) ; 6
```
