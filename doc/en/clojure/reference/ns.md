# ns

`(ns name clauses...)`

Declares a namespace and wires its clauses, defining nothing: `:as` registers an alias,
`:refer`/`:use` unqualified names, `:import` class names for interop, and
`(:refer-clojure :only ...)`/`(:refer-clojure :exclude ...)` narrow the visible core. There is
no `:rename`. Only `clojure.string` resolves; an unknown namespace is an error. The namespace
itself stays flat -- the name is bookkeeping.

```clojure
(ns demo (:require [clojure.string :as s :refer [join]]))
(println (s/upper-case "hi")) ; HI
(println (join "-" ["a" "b"])) ; a-b
```
