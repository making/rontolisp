# e42. The namespaces clojure.jar bundles beyond string/set/test/java.io

Difficulty: High

Only `clojure.string`, `clojure.set`, `clojure.java.io` (`reader` only), `clojure.test` and
the Ring namespaces resolve; every other `clojure.*` is `unknown namespace`. Libraries pulled
in by `deps.edn` require the rest of clojure.jar routinely, so most fail at their `ns` form
before any of their own code is reached.

## Gaps (order by how often libraries require them; measure in step 1)

`clojure.walk`, `clojure.edn`, `clojure.pprint`, `clojure.java.io` (beyond `reader`),
`clojure.data`, `clojure.zip`, `clojure.template`, `clojure.core.protocols`,
`clojure.instant`, `clojure.uuid` (`#inst`/`#uuid` tagged literals), `clojure.main`,
`clojure.repl`, `clojure.core.reducers`, `clojure.java.shell`.

## Decisions to make

- Shipping per namespace: Clojure source in the jar (the Ring precedent,
  `ClojureBuiltinNamespaces`) vs a `Clojure*Lowering` slice (the `clojure.set` precedent).
  The Ring measurement (`.kb/clojure-frontend.md`, "Ring util namespaces") is the guide:
  map-shaped code as source, byte/string work as CL kernels.
- Licensing: clojure.jar's source is EPL-1.0; this project is Apache-2.0. Copying it in is a
  license question to settle before any file is added; a clean-room port avoids it.

## Plan

1. Count `require`s of each namespace across the libraries `e43` probes; take the top ones.
2. One namespace per commit, oracle-diffed `clojure-spec.yaml` lines, four backends.
3. `.kb/clojure-frontend.md`; `doc/en` + `doc/ja` reference.
