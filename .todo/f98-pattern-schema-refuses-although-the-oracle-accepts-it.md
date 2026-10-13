# f98. `(m/schema #"...")` refuses although the oracle accepts it

Difficulty: Medium

Found closing `.todo/f96` (2026-10-13): f96 makes `type` answer the host class
designator for the kinds whose keyword would collide with a schema registry
entry, so an unregistered value refuses with `:malli.core/invalid-schema` like
the oracle. Patterns keep answering `:pattern` on purpose: `(type #"a")` is
`:pattern` here, `java.util.regex.Pattern` on the oracle (verified `clj`
1.12.6, 2026-10-13), and only the oracle's answer hits the registry's
`class-schemas` entry, so `(m/schema #"a")` builds the regex schema there and
refuses here.

## What decides the design

- Accepting needs the type answer to hit the registered `Pattern` class: the
  host `Class` object where a host is, the `(:C%CLASS name)` designator where
  none is (a bare class literal already splits this way). A designator on a
  host misses the stored `Class` key, so one shape cannot serve both.
- Out of scope: the f96 refusal paths (untouched).

## Plan

1. `(m/schema #"a")` builds the regex schema, with the oracle's `form`, on all
   four backends (or documents why it cannot).
2. clojure-spec rows where the behavior is observable, four backends.
