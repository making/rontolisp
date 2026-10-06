# d51. The `string=` lowering evaluates its bounds out of order and drops a repeated keyword

Difficulty: Low

The compile paths lower `(string= s1 s2 :start1 a :end1 b ...)` (and `string-equal`) onto
`(string= (subseq (string s1) a b) (subseq (string s2) c d))`
(`LispMacroExpander.expandStringComparisonBounds`), so the forms run in the lowering's order,
not the call's, and a repeated keyword keeps the LAST value without evaluating the first.
The interpreter and the `string<` family (a `&key` defun) follow CLHS 3.4.1.4.

| program | SBCL / interpreter | JVM (P1 / component alike) |
|---|---|---|
| `(string= (n :s1 "abc") (n :s2 "abc") :end1 (n :e1 3) :start1 (n :st1 0))`, log order | `(:S1 :S2 :E1 :ST1)` | `(:S1 :ST1 :E1 :S2)` |
| `(string= "abc" "abc" :start1 (n :a 1) :start1 (n :b 0))` | `NIL`, log `(:A :B)` | `T`, log `(:B)` |

`n` pushes its tag and answers its value. Measured 2026-10-06.

Fix: hoist the operands and every keyword value in source order before the cut --
`LispMacroExpander.KeywordTail` already does this for the sequence scans
(`.kb/sequence-designator-evaluation.md`) -- first occurrence wins, later ones still evaluated.
Keep a keyword-free call byte-identical (it never reaches the lowering) and a call whose
operands and bounds are variables or literals unchanged; pin on all four backends.
