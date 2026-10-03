# c30. Clojure: a symbol in call position or as a function value signals

Difficulty: Low

`('a {'a 1})` and `(map 'a [{'a 2}])` answer `not a function` (2026-10-03, all backends through
`rontolisp::%clojure-call`); the oracle (`clj` 1.12.6) answers `1` and `(2)`, like a keyword: a symbol is an
IFn that looks itself up in a map or set, with an optional default. `ifn?` already answers `true` for a symbol.

Add a symbol arm to `%clojure-call` (and `%clojure-as-fn` callers) reading like `%clojure-call-keyword`
(a set answers the member, a map/record the value, anything else the default). Pin on all four backends
in `clojure-spec.yaml`. Note the existing deviation the other way: a record is callable here
(`((->R 1) :a)` answers 1) where the oracle signals (a record is no IFn).
