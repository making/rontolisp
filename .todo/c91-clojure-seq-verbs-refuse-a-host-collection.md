# c91. Clojure seq verbs refuse a host collection

Difficulty: Medium

`(def al (java.util.ArrayList. [1 2]))`, `(def hm (doto (java.util.HashMap.) (.put "a" 1)))`:
on the interpreter and the JVM `(seq al)`, `(vec al)`, `(first al)`, `(map inc al)`,
`(reduce + al)` and `(into {} hm)` are `seq needs a collection`, `(count al)` /
`(count hm)` a `LENGTH` type-error, `(get hm "a")` nil. The oracle (clj 1.12.6, measured
2026-10-04) answers `(1 2)`, `[1 2]`, `1`, `(2 3)`, `3`, `{a 1}`, `2`/`1`, `1`: `RT.seq`
takes any `Iterable` (a `Map` through its `entrySet`, each entry a `[k v]`), `count` any
`Collection`/`Map`, `get` any `Map`. Undocumented in `doc/*/clojure/deviations.md`.
Likely a host arm (`ClojureArms.Family.HOST`, a `java:` program only) in `%clojure-seq`'s
fall-through reading `toArray` like `%clojure-host-collection-equal`, plus `count`/`get`
arms; measure what a program without `java:` pays (must be nothing).
