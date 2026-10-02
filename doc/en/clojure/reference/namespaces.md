# Namespaces

A namespace form wires its clauses and defines nothing: `:as` registers an alias, `:refer`/`:use` unqualified names, `:import` class names for interop, `(:refer-clojure :only/:exclude ...)` narrows the visible core. `clojure.string`, `clojure.java.io` (`reader` only) and `clojure.test` resolve; requiring an unknown namespace is an error. The namespace itself stays flat.

| Name | Example | Result |
|---|---|---|
| `ns` | `(do (ns demo (:require [clojure.string :as s])) (s/upper-case "hi"))` | `HI` |
| `require` | `(do (require '[clojure.string :as s]) (s/join "," ["a"]))` | `a` |
| `use` | `(do (use '[clojure.string :only [upper-case]]) (upper-case "hi"))` | `HI` |
| `import` | `(do (import java.util.Date) nil)` | `nil` |
| `in-ns` | `(do (in-ns 'demo) nil)` | `nil` |
