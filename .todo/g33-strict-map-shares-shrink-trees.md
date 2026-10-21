# Strict map realizes test.check shrink trees eagerly (large choose ranges hang)

Difficulty: Hard

Cut from g28 (2026-10-21): the 12-call seeded probe stays with small-valued
generators (`choose` ranges within about 0..25) because anything larger never
answers -- on any backend, however fast. `int-rose-tree` builds the shrink
tree of its value through `(core/map int-rose-tree (shrink-int value))`, and
`map` is strict over a strict input (`.kb/clojure-frontend.md`, "Laziness":
lazy only when an input is lazy). The child count of a value near 2^k is
about k, each child nearly as large, so the eager tree has on the order of
k^k nodes: measured node counts are 20 for 5, 254 for 10, 37,954 for 20,
2.6e9 for 42 and 7.5e26 for 126. The oracle's `map` is always lazy, so only
the consumed spine exists.

## What to build

- Either a laziness story for this shape that keeps the pinned lazy-or-strict
  rule (the per-verb pins and the strict-loop performance notes in
  "Laziness" stand: a strict 200,000-element `for` must not pay a thunk per
  element), or a documented boundary: which generator magnitudes answer and
  which hang, with the measured table above.
- Whichever it is, extend the g28 probe with a larger-valued draw (or note
  why the small-valued set is the supported boundary) and pin the boundary
  with a clojure-spec case.
- g31's wasm statics must land before this matters on wasm at all; the
  interpreter/JVM legs hang today on e.g. `(gen/generate (gen/choose 0 100)
  5 99)`.

## Non-goals

- Changing the global `map` strictness (the lazy-or-strict pins and their
  performance basis).
- Anything in the mix functions themselves (g28): the draws are exact, only
  the shrink realization diverges.

## Context

g28 (`.todo/g28-unchecked-math-wrapping.md`): seeded draws of `choose` 0..10
and `small-integer`/`nat` at small sizes print the oracle's lines on interp
and JVM because their shrink trees stay tiny. test.check 1.1.3, no transitive
deps.
