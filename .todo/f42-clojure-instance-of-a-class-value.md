# f42. Clojure: `instance?` of a class value

Difficulty: Medium

instaparse 1.5.0 asks `(instance? gll/failure-type result)` (`core.clj:337`), where
`failure-type` is `(type (Failure. nil nil))`: a class held in a var. Here `instance?` takes a
class NAME only and refuses anything else while lowering (`instance? takes a class name, not
gll/failure-type`), found 2026-10-11 by removing the stops before it by hand in a copy of the
library.

Measured against clj 1.12.6:

| program | oracle |
|---|---|
| `(defrecord R [a]) (def rt (type (->R 1))) [(instance? rt (->R 2)) (instance? rt 3)]` | `[true false]` |
| `[(instance? (class "s") "t") (instance? (class 1) 2)]` | `[true true]` |

## What decides the design

- `.kb/clojure-frontend.md` "Dispatch": `class` answers a kind keyword (a record its tag, an
  exception its class name, a host object its class object), `instance?` is a lower-time
  disjunction per kind (`ClojureValueClasses`), "Class chains" the run-time rows.
- The answer is `isInstance` of what `class`/`type` answered: a keyword's kind, a tag, a
  chained class keyword, a host class object.

## Plan

1. `instance?` whose class is no symbol: a run-time test of the value against the class
   value as `class` answers it (exact kind or tag, the class rows for supers, `isInstance` of
   a host class object), behind an arm a program with no such site sheds.
2. Re-probe instaparse; pin on all four backends.
