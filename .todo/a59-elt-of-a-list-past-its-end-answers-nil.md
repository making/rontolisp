# elt of a list past its end, or at a negative index, answers instead of signalling

Difficulty: Low

Measured 2026-09-27 on all four backends: `(elt '(1 2) 5)` answers `NIL` and `(elt '(1 2) -1)`
answers `1`. CL: `elt` signals a `type-error` for an index that is not a valid sequence index (SBCL
reports the index too large for the list's length); a vector's `elt` already reports
`AREF: The value 5 is not of type (INTEGER 0 (2))`. The list arm is `nth`, which answers nil past
the end and whose negative index reached the list itself.

Goal: the list arm of `elt` (`LispMacroExpander.expandElt`, the interpreter's `elt`) checks the
index against the list's length and signals the `type-error` with the same text on every backend,
without an O(n) length walk on the hit path (the walk itself can report when it runs out).
