# Namespaces

Every namespace has its own vars: a definition belongs to the current namespace, a name resolves to that namespace's own var and then to a referred one, and `alias/name` or `full.name/name` reaches another namespace's public var. A namespace form switches to its namespace and wires its clauses: `:as` registers an alias, `:refer`/`:use` unqualified names, `:import` class names for interop, `(:refer-clojure :only/:exclude ...)` narrows the visible core; metadata on the name (`^{...}`, `#^{...}`), a docstring and an attr map are skipped. The [built-in namespaces](#built-in-namespaces) need no file; any other namespace is one the program declares with `ns`, or one loaded once from its file on the source path ([Semantics](../semantics.md#namespaces-and-files)). A namespace no root holds is an error ([require](require.md): inside a function or a `try`, where the code runs).

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
| `ns-resolve` | `(ns-resolve 'clojure.core 'inc)` | `#'clojure.core/inc` |
| `all-ns` | `(contains? (set (map str (all-ns))) "clojure.core")` | `true` |
| `create-ns` | `(ns-name (create-ns 'demo))` | `demo` |
| `remove-ns` | `(do (create-ns 'gone) (remove-ns 'gone) (some? (find-ns 'gone)))` | `false` |
| `loaded-libs` | `(do (require 'clojure.string) (contains? (set (map str (loaded-libs))) "clojure.string"))` | `true` |
| `intern` | `(intern 'user 'eg 7)` | `#'user/eg` |
| `var-set` | `(try (var-set (intern 'user 'eg) 1) (catch IllegalStateException e :refused))` | `:refused` |
| `with-redefs-fn` | `(def rd-x 1) (with-redefs-fn {(var rd-x) 99} (fn [] rd-x))` | `99` |
| `ns-publics` | `(get (ns-publics 'clojure.core) 'map)` | `#'clojure.core/map` |
| `ns-interns` | `(contains? (ns-interns 'clojure.core) 'map)` | `true` |
| `ns-map` | `(get (ns-map 'user) 'map)` | `#'clojure.core/map` |
| `ns-refers` | `(do (refer 'clojure.string :only '[join]) (some? (get (ns-refers 'user) 'join)))` | `true` |
| `ns-aliases` | `(do (alias 's 'clojure.string) (ns-name (get (ns-aliases 'user) 's)))` | `clojure.string` |
| `ns-imports` | `(get (ns-imports 'user) 'String)` | `java.lang.String` |
| `alias` | `(do (alias 's 'clojure.string) (s/join "-" ["a"]))` | `a-b` |
| `refer` | `(do (refer 'clojure.string :only '[upper-case]) (upper-case "ab"))` | `AB` |
| `refer-clojure` | `(do (refer-clojure :rename '{map core-map}) (core-map inc [1 2]))` | `(2 3)` |
| `ns-unmap` | `(do (refer 'clojure.string :only '[join]) (ns-unmap 'user 'join) (get (ns-refers 'user) 'join))` | `nil` |
| `ns-unalias` | `(do (alias 's 'clojure.string) (ns-unalias 'user 's) (get (ns-aliases 'user) 's))` | `nil` |
| `requiring-resolve` | `(requiring-resolve 'clojure.string/join)` | `#'clojure.string/join` |
| `load-string` | `(load-string "(+ 1 2)")` | `3` |
| `load-file` | `(load-file "helper.clj")` | the file's last value |

## Built-in namespaces

A built-in namespace written in Clojure loads like a project file, after every source root,
so a file of its name on the source path takes precedence -- except `clojure.walk`,
`clojure.core.protocols`, `clojure.instant`, `clojure.uuid`, `clojure.java.io` and `clojure.main`, which are
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
| `clojure.java.shell` (interpreter and JVM) | [clojure.java.shell](clojure-java-shell.md) |
| `clojure.xml` | [clojure.xml](clojure-xml.md) |
| `clojure.repl` | [clojure.repl](clojure-repl.md) |
| `clojure.main` | [clojure.main](clojure-main.md) |
| `clojure.test` | [Tests (clojure.test)](test.md) |
| `ring.adapter.rontolisp` | [Ring adapter](ring.md) |
| `ring.util.*`, `ring.middleware.*` | [Ring utilities](ring-util.md) |
| `rontolisp.http-client` | [HTTP client](http-client.md) |
| `rontolisp.http-urls` (no vars: `clojure.java.io` reads `http:` URLs through `rontolisp:fetch`) | [HTTP URLs](clojure-java-io.md#http-urls) |
