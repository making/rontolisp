# Clojure: host-boolean `T`-or-false for `if-let`/`when-let` and `..`-chained receivers

Difficulty: Small (extends the b29 `let`-inference; same wrap, new record sites).

## Gap

b29 wraps an instance call in `T`-or-false when the receiver's class is known: a
construction literal, or a `let` local bound to one. Two shapes that also hold a
known class stay unknown and print `nil` for `false`:

- `if-let`/`when-let` (and `when-first`) build their own `let*` instead of going
  through `let()`, so a pattern bound to a construction literal records nothing:
  `(if-let [a (java.util.ArrayList.)] (.isEmpty a) :e)` prints `nil` for a
  non-empty list where the oracle prints `false`.
- A `..` chain whose outer step calls a boolean method on an inner call's answer:
  `(.. (java.util.ArrayList. [1]) (subList 0 1) (isEmpty))` -- the outer receiver
  is a `java:call` result, not a construction, so it keeps the unmarshal.

## Design sketch

- Record in `ifLetOf`/`whenLetOf` the way `let` does (same `noteHostClass` plus
  the same finally cleanup; the binding is single-shot, so the depth-walk
  soundness carries over). `when-first` binds collection members (never a
  construction) -- forget only.
- For `..`, the outer receiver's class would have to come from the inner call's
  declared return type (the resolver's `ofDeclared`), not from a literal -- a
  small type-propagation step, or leave `..` unknown and document it.

## Acceptance

- `ClojureInteropTest`: the `if-let` shape above prints `false` on the
  interpreter + JVM; whatever is decided for `..` is pinned beside it (or kept
  as the documented unknown-receiver deviation).
- `.kb/clojure-frontend.md` host-boolean paragraph updated; docs en+ja same commit
  if the user-visible rule changes.

## Depends on

b29 (the wrap, the `HostClass` record and the shadow walk). Independent of b26.
