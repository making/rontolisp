# Namespaces

Every namespace has its own vars: a definition belongs to the current namespace, a name resolves to that namespace's own var and then to a referred one, and `alias/name` or `full.name/name` reaches another namespace's public var. A namespace form switches to its namespace and wires its clauses: `:as` registers an alias, `:refer`/`:use` unqualified names, `:import` class names for interop, `(:refer-clojure :only/:exclude ...)` narrows the visible core; metadata on the name (`^{...}`, `#^{...}`), a docstring and an attr map are skipped. The [built-in namespaces](#built-in-namespaces) need no file; any other namespace is one the program declares with `ns`, or one loaded once from its file on the source path ([Semantics](../semantics.md#namespaces-and-files)). A namespace no root holds is an error.

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

## Built-in namespaces

A built-in namespace written in Clojure loads like a project file, after every source root,
so a file of its name on the source path takes precedence -- except `clojure.walk`,
`clojure.core.protocols`, `clojure.instant`, `clojure.uuid` and `clojure.java.io`, which are
loaded before the program, as in Clojure: a qualified name such as `clojure.walk/postwalk` reaches one without
a `require`, as it does `clojure.edn`'s and `clojure.string`'s. A `clojure.*` namespace that is not
part of Clojure itself (a contrib library such as `clojure.data.json`) loads from the
source path like any other; any other namespace of Clojure's own is an error.

| Namespace | Page |
|---|---|
| `clojure.string` | [(clojure.string)](string.md) |
| `clojure.set` | [(clojure.set)](clojure-set.md) |
| `clojure.walk` | [clojure.walk](clojure-walk.md) |
| `clojure.edn` | [clojure.edn](clojure-edn.md) |
| `clojure.instant` | [clojure.instant](clojure-instant.md) |
| `clojure.uuid` (no vars: `#uuid` reads through the reader) | [Instants and UUIDs](instants.md) |
| `clojure.data` | [clojure.data](clojure-data.md) |
| `clojure.zip` | [clojure.zip](clojure-zip.md) |
| `clojure.datafy`, `clojure.core.protocols` | [clojure.datafy](clojure-datafy.md) |
| `clojure.core.reducers` | [clojure.core.reducers](clojure-core-reducers.md) |
| `clojure.stacktrace` | [clojure.stacktrace](clojure-stacktrace.md) |
| `clojure.math` | [clojure.math](clojure-math.md) |
| `clojure.pprint` | [clojure.pprint](clojure-pprint.md) |
| `clojure.template` | [clojure.template](clojure-template.md) |
| `clojure.java.io` | [clojure.java.io](clojure-java-io.md) |
| `clojure.test` | [Tests (clojure.test)](test.md) |
| `ring.adapter.rontolisp` | [Ring adapter](ring.md) |
| `ring.util.*`, `ring.middleware.*` | [Ring utilities](ring-util.md) |
| `rontolisp.http-client` | [HTTP client](http-client.md) |
