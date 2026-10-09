# e98. Clojure: `get-in`/`assoc-in`/`update-in` over a computed key vector

Difficulty: Medium

The three verbs take only a literal key vector: `ClojureUpdateLowering.keysVector` unrolls it
at lower time and refuses anything else (`get-in takes a vector of keys, not |index|`). The
oracle takes any seqable of keys at run time. Measured 2026-10-09: instaparse 1.5.0 stops
there, past the macro-time `eval` (`auto_flatten_seq.clj:58:12`, `(get-in v index)` over a
parameter); the message also spells the local in Common Lisp notation (`|index|`).

## Plan

1. Measure on clj 1.12.6: a key seq that is a vector, a list, nil and empty; a missing level;
   `get-in`'s not-found; `assoc-in`/`update-in` with an empty path.
2. A non-literal key path lowers to one run-time worker per verb (the literal path keeps its
   unrolled form); a clojure-spec case on all four backends.
3. Re-probe instaparse 1.5.0 (it also hashes through `hash`, e88).
