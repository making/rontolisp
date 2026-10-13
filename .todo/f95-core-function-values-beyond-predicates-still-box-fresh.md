# f95. Core function values beyond predicates still box fresh per reference

Difficulty: Medium

Found closing `.todo/f92` (2026-10-13): f92 shares every site-independent
predicate value program-wide through `%clojure-shared-value`, so two
references to `pos-int?` are `=` and `identical?` like the oracle's var roots.
Every other closure-valued core name still builds a fresh lambda per reference
site, so it is never `=` to itself, where the oracle answers `true`:

- `(= map map)`, `(= filter filter)`, `(= str str)`, `(= count count)`,
  `(= inc inc)` are `false` here, `true` on `clj` 1.12.6;
- `(= distinct? distinct?)`, `(= apply apply)`, `(= + +)` are already `true`
  (a `(function ...)` of a library `defun`).

## What decides the design

- The `%clojure-shared-value` worker only fits rows whose body closes over
  nothing but its parameters. A seq verb (`map`, `filter`, `seq`, `first`,
  `rest`, `cons`, `take`, `drop`, `concat`, `reduce`, `apply` aside),
  `comp`/`partial`/`complement`/`constantly`/`identity`/`memoize`/`trampoline`,
  the state verbs, the constructors (`keyword`, `symbol`, `char`, ...), `not=`
  and the comparisons each need their own shared shape (a program-wide `defun`
  plus a `(function ...)` reference, or the worker where the body qualifies).
- Parameterized values (a keyword's lookup, a map or set) compare as their
  value already and need nothing.
- Out of scope: the predicate family (f92).

## Plan

1. Two references to each named core value are `=` and `identical?`, like the
   oracle (clj 1.12.6).
2. clojure-spec rows where the behavior is observable, four backends.
