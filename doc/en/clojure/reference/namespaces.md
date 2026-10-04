# Namespaces

Every namespace has its own vars: a definition belongs to the current namespace, a name resolves to that namespace's own var and then to a referred one, and `alias/name` or `full.name/name` reaches another namespace's public var. A namespace form switches to its namespace and wires its clauses: `:as` registers an alias, `:refer`/`:use` unqualified names, `:import` class names for interop, `(:refer-clojure :only/:exclude ...)` narrows the visible core; metadata on the name (`^{...}`, `#^{...}`), a docstring and an attr map are skipped. `clojure.string`, `clojure.set`, `clojure.java.io` (`reader` only) and `clojure.test` are built in; any other namespace is one the program declares with `ns`, or one loaded once from its file on the source path ([Semantics](../semantics.md#namespaces-and-files)). A namespace no root holds is an error.

| Name | Example | Result |
|---|---|---|
| `ns` | `(do (ns demo (:require [clojure.string :as s])) (s/upper-case "hi"))` | `HI` |
| `require` | `(do (require '[clojure.string :as s]) (s/join "," ["a"]))` | `a` |
| `use` | `(do (use '[clojure.string :only [upper-case]]) (upper-case "hi"))` | `HI` |
| `import` | `(do (import java.util.Date) nil)` | `nil` |
| `in-ns` | `(do (in-ns 'demo) nil)` | `nil` |
| `the-ns` | `(str (the-ns 'user))` | `"user"` |
| `find-ns` | `(find-ns 'no-such)` | `nil` |
| `ns-name` | `(ns-name *ns*)` | `user` |
