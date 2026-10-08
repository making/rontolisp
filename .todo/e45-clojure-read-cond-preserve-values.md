# e45. `{:read-cond :preserve}`: ReaderConditional and TaggedLiteral values

Difficulty: Medium

`read-string`/`read` take `{:read-cond :allow}` (`.kb/clojure-frontend.md`, "Reader
conditionals"); `:preserve` is refused at the first `#?`, because the oracle answers value
kinds no backend has:

- `#?(:clj 1 :cljs 2)` is a `clojure.lang.ReaderConditional`: `pr` spells it back
  (`#?@(...)` when splicing), `(:form rc)` is the list, `(:splicing? rc)` the flag,
  `reader-conditional?` true, `=` by form and flag. `[#?@(:clj [1 2])]` keeps it as one
  member.
- Inside one, a tagged literal reads as a `clojure.lang.TaggedLiteral` (`#js {}`, `#foo 1`
  print back), while at the top level `#foo/bar 1` is still `No reader function`.
- `reader-conditional`, `tagged-literal`, `tagged-literal?` (`ClojurePredicateLowering.NEVER`
  answers false today).

## Plan

1. Measure on `clj` 1.12.6: printing, `get`/keyword lookup, `=`/hash, `class`, nesting.
2. Two wrappers with a `ClojureArms` family whose producers are the reader entry points and
   the constructors, so a program that reads nothing keeps its bytes.
3. clojure-spec lines, four backends; `doc/en` + `doc/ja` (`read-string`, deviations).
