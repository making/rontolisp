# c36. Clojure: a double past the plain range prints `1.0e19`, the oracle `1.0E19`

Difficulty: Low

`(println 1.0E19 (str 1.0E19) (pr-str 1.5e-7) [1e300])` prints `1.0e19 1.0e19 1.5e-7 [1.0e300]` on
every backend (2026-10-03); the oracle (`clj` 1.12.6, Java's `Double.toString`) prints `1.0E19 1.0E19
1.5E-7 [1.0E300]`. Not a documented deviation. The Clojure printer (`%clojure-write`'s fall-through
`princ`, and `%clojure-str-of`) goes through the Common Lisp float printer, whose exponent marker is
lowercase.

Spell the marker uppercase in the Clojure printer only (the CL printer is right as it is), on all four
backends, and pin it in `clojure-spec.yaml`; check `format`'s `%s` of a double too.
