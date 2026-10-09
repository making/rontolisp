# f05. Clojure: transients

Difficulty: Medium

`transient`, `persistent!`, `conj!`, `assoc!`, `dissoc!`, `disj!` and `pop!` are refused by
name (`transients are not supported yet: ...`, `.kb/clojure-frontend.md` "The lowering
table"). Measured 2026-10-09: instaparse 1.5.0 stops at `auto_flatten_seq.clj:302`
(`flat-vec-helper`'s `conj!`) past f04 (its hints removed by hand); medley stops at `assoc!`
(`.kb/clojure-frontend.md` "Reading" re-probe, 2026-10-08).

## What decides the design

- A transient answers the collection the bang verb returns, and the oracle refuses a
  transient used after `persistent!` (`Transient used after persistent! call`); a program may
  ignore a bang verb's answer only by accident (the oracle's `assoc!` of a small map answers a
  new object past eight entries).
- `count`, `get`, `nth`, `contains?` and a call read a transient; `seq` refuses one.

## Plan

1. Measure on clj 1.12.6 each verb over vectors, maps and sets, the reads above, the refusals
   (after `persistent!`, `seq`, a non-editable collection), and `class`/`instance?`.
2. A transient wrapper over the persistent value the bang verbs replace, the reads through it,
   the refusals in the oracle's words, on all four backends.
