# ns

`(ns name clauses...)`

Switches to the namespace, creating it, and wires its clauses: `:as` registers an alias,
`:refer`/`:use` unqualified names, `:import` class names for interop, and
`(:refer-clojure :only ...)`/`(:refer-clojure :exclude ...)` narrow the visible core.
`:as-alias` in a `:require` libspec registers an alias without loading the namespace, and `:rename {old new}`
refers a var under another name (the old name is not referred); `(:refer-clojure :rename {old new})` does the
same for a core var. `(:load "path" ...)` loads files like [`load`](../semantics.md#namespaces-and-files),
and `(:gen-class ...)` is accepted and ignored: no class is generated. The definitions below the form belong to the namespace. A required
namespace other than `clojure.string`, `clojure.set`, `clojure.java.io` (`reader` only), `clojure.test` and
`ring.adapter.rontolisp` ([Ring adapter](ring.md))
is a project namespace: one an earlier `ns` form of the program declared, or one whose
file on the source path loads when the clause runs -- once per program, again under
`:reload` ([Semantics](../semantics.md#namespaces-and-files)). The built-in
[Ring utilities](ring-util.md) load the same way when no root holds a file of their name;
any other file no root holds is an error.

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
