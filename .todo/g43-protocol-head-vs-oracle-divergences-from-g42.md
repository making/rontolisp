# g43: Protocol head-vs-oracle divergences found during g42

Difficulty: Medium

Parent: `.todo/g42-run-time-value-arms-for-satisfies-extends-extend.md` (closed;
this item collects the head-position divergences from the oracle that g42's
differential probing proved and deliberately did not touch -- the value arms
mirror the head, so all three are shared head+value deviations, pinned by
clojure-spec `protocol-value-arms-run-where-calls-run` with the oracle
difference noted in its comment).

## What diverges

All verified against `clj` 1.12.6 on 2026-10-22 (see the g42 worktree probes
`/tmp/opencode/g42*.clj`, since removed):

1. **nil answers the `Object` default.** The oracle never consults the `Object`
   impl for nil (`find-protocol-impl` guards the default behind `(and c ...)`),
   so `(satisfies? P nil)` is false and `(P nil)` refuses when `P` is extended
   to `Object` but not to nil. Here the dispatchers and `satisfies?` read the
   default for a nil tag miss, answering true / dispatching. (Heads
   `dispatcherDefun` / `satisfiesOf`; the value arms mirror them.)
2. **`extend` with a non-map method map signals.** The oracle stores any mmap
   as-is (`assoc-in [:impls atype]`, replacing the type's map, even a string or
   a number). A computed head `extend` signals `extend needs a map or a vector`
   (via `%clojure-kv-pairs`); a literal non-map is a lower-time shape refusal.
   The g42 value arm ignores non-map mmaps (spec'd); the head paths do not.
3. **`extend` skips the `implements?` guard.** The oracle refuses
   `class X already directly implements interface I for protocol:#'ns/P` when
   the type's class already implements the protocol (a body implementation).
   The head `extend`/`extend-type`/`extend-protocol` store regardless (only a
   duplicate method is refused). The g42 value arm likewise installs.
4. **Row merge vs wholesale replace.** The oracle's `extend` replaces the
   type's whole impl map; every head path merges rows into the inner table
   (and the g42 value arm with it).

## Acceptance

- Each item fixed or documented as intended; clojure-spec rows diffed against
  the oracle on all four backends; no head call's lowered shape changes
  without its pin moving with it.
