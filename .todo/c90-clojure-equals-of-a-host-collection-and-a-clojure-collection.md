# c90. Clojure `=` of a host collection and a Clojure collection

Difficulty: Medium

`(def al (java.util.ArrayList. [1 2]))`: `(= al [1 2])`, `(= [1 2] al)` and `(= al '(1 2))` are
`false` on the interpreter and the JVM, `true` in the oracle (clj 1.12.6, measured
2026-10-04), whose `Util.equiv` sends a pair with an `IPersistentCollection` to `pcequiv`
and compares a `java.util.List` element-wise both ways (`Map`/`Set` likewise; `HashMap`'s
one-argument constructor from a Clojure map is refused here, so the map/set rows need
building another way). `%clojure-equal`'s sequential/map/set arms do not take a host
collection, and CL `equal` hands a host object's `equals` atoms only (`.kb/eq-numbers.md`,
"Host objects", which explains why a list is not converted there). The oracle's lookups are
not symmetric with it: `(get {[1 2] :v} al)` is `:v`, `(contains? #{[1 2]} al)` `false`.
Deciding it needs a host arm in `%clojure-equal` (a `java:` program only, like the other
host arms; `ClojureArms.Family.HOST`) that walks a host `List`/`Map`/`Set` against the
Clojure kind, and a look at what `%clojure-hash` / the structural keys should do with one.
