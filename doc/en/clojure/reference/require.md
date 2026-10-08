# require

`(require 'clause ...)`

Loads namespaces and wires each clause's `:as` alias and `:refer`ed names, answering `nil` --
the same wiring `ns` does, spelled at top level with quoted libspecs. `clojure.string`,
`clojure.set`, `clojure.java.io` (`reader` only), `clojure.test` and `ring.adapter.rontolisp` are built in; any other namespace loads
once per program from its file on the source path ([Semantics](../semantics.md#namespaces-and-files)),
and one no root holds is an error. Only `:refer` refers names: a bare `:only` refers nothing,
like the oracle. `:rename {old new}` refers a referred var under `new` instead of `old`;
`:as-alias` registers an alias without loading the namespace (`::alias/k` and a syntax-quoted `alias/x` spell its name).
An unquoted vector spec is accepted too, though real Clojure rejects it.
A prefix list `'(prefix [sub ...])` wires each member under the prefix, quoted or bare.
`:reload` runs each named namespace again (`def` resets, `defonce` keeps its root);
`:reload-all` re-runs their dependencies first. A `require` inside a function body loads
when the body runs, answering `nil` like any other `require`.

```clojure
(require '[clojure.string :as s])
(println (s/join "," ["a"])) ; a
```
