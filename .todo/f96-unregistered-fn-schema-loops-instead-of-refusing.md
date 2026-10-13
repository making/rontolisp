# f96. `(m/schema <unregistered-fn>)` loops instead of refusing like the oracle

Difficulty: Medium

Found closing `.todo/f92` (2026-10-13): with predicate values shared, a
registered predicate reaches its registry entry directly. An UNREGISTERED
function value still loops until the stack overflows, on every backend: the
direct registry miss falls into `-lookup`'s `(type x)` branch, `(type fn)` is
`:function`, which hits the `:function` schema, whose `-into-schema` re-schemas
its fn child, which misses again. The oracle refuses with
`:malli.core/invalid-schema` (verified `clj` 1.12.6, 2026-10-13; an
unregistered keyword refuses here too, with `:malli.core/child-error` instead
of the oracle's `:invalid-schema`).

## What decides the design

- The loop lives in malli's `-lookup`/`-function-schema`, which this front end
  cannot change; the observable inputs are `(type fn)` (`:function` here, a
  class on the oracle) and the registry's `:function` entry. Fixing it needs a
  refusal before the fallthrough (only where the oracle refuses) or a
  `:function`-branch guard, without breaking the registered-predicate hit.
- Out of scope: the registered-predicate path (f92).

## Plan

1. `(m/schema (fn [x] x))` refuses with the oracle's words on all four
   backends (or documents why it cannot).
2. clojure-spec rows where the behavior is observable, four backends.
