# c12. Clojure `<` `>` `<=` `>=` `zero?` ... as function values answer `nil`, not `false`

Difficulty: Low

Measured 2026-10-03, `(prn (map < [1 2] [2 1]) (map zero? [0 1]) (map >= [1 2] [2 1]))`:
the oracle (clj 1.12.6.1673) prints `(true false) (true false) (false true)`, every backend
here prints `(true nil) (true false) (nil true)`. `zero?` is right; the names `builtinValue`
maps straight to a CL function (`<` `>` `<=` `>=`, `evenp`/`oddp`/`plusp`/`minusp` if they
share the path) answer `NIL` as a value, while the call form goes through `booleanAnswer`.
`.kb/clojure-frontend.md` says predicates answer `T`-or-false as values.

## Plan

- Give each such name a `valueOf` lambda over `&rest` answering `T`-or-false, like
  `numericEqualValue` (`==`) and `notEqualValue`.
- Pin in clojure-spec with the oracle's output (`map` of each over a mixed vector).
