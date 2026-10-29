# Clojure: `clojure.spec.alpha` loads from its own source

Difficulty: High

`(require '[clojure.spec.alpha :as s])` is `unknown namespace`; `.kb/clojure-frontend.md`
"`clojure.spec`: refused" keeps it out until an in-scope program needs `valid?`/`conform`.
cljw 1.15.4 bundles it (`(s/valid? int? 1)` is `true`), and malli/specs-based libraries
and `defn` specs (`clojure.core.specs.alpha`) need it. The coordinates
`org.clojure/spec.alpha` / `core.specs.alpha` already resolve to "this front end"
(`ClojureBuiltinLibs`), so nothing is fetched.

Approach: stop short-circuiting the coordinate and load spec.alpha's real source from its
Maven jar like any library probe, fixing blockers as infrastructure. Start with
`valid?`/`conform`/`explain-str`/`s/def`/`s/keys`; `instrument`/`check` (spec.gen over
test.check) after. Rewrite the kb section with what the probe shows.
