# require

`(require 'clause ...)`

Loads namespaces and wires each clause's `:as` alias and `:refer`ed names, answering `nil` --
the same wiring `ns` does, spelled at top level with quoted libspecs. A
[built-in namespace](namespaces.md#built-in-namespaces) needs no file; any other namespace loads
once per program from its file on the source path ([Semantics](../semantics.md#namespaces-and-files)),
and one no root holds is an error. Only `:refer` refers names: a bare `:only` refers nothing,
like the oracle. `:rename {old new}` refers a referred var under `new` instead of `old`;
`:as-alias` registers an alias without loading the namespace (`::alias/k` and a syntax-quoted `alias/x` spell its name).
An unquoted vector spec is accepted too, though real Clojure rejects it.
`clojure.core` itself loads nothing: `[clojure.core :as c]` makes `c/x` spell the core
var `clojure.core/x` (malli's and cuerdas' alias), and its `:refer` brings back a name
`(:refer-clojure :exclude [...])` left out.
A prefix list `'(prefix [sub ...])` wires each member under the prefix, quoted or bare, and so
does a vector whose second element is no keyword (`'[clojure [set :as s] walk]`), like the oracle.
`:reload` runs each named namespace again (`def` resets, `defonce` keeps its root);
`:reload-all` re-runs their dependencies first. A `require` inside a function body loads
when the body runs, answering `nil` like any other `require`; there, and inside a `try` or a
`when`, a namespace no root holds fails only when the code runs, with the oracle's
`FileNotFoundException` (`Could not locate a/b__init.class, a/b.clj or a/b.cljc on
classpath.`), so an optional library's `(try (require 'x) (catch Exception e ...))` works.
As a value every application refuses at run time, since libraries load while the program
lowers (of no spec, the oracle's `Nothing specified to load`).

```clojure
(require '[clojure.string :as s])
(println (s/join "," ["a"])) ; a
```
