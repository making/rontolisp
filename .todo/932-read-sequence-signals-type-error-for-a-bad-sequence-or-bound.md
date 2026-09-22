# `read-sequence` / `write-sequence` signal `type-error` for a bad sequence or bound

Difficulty: Low

Split out of `.todo/920` (2026-09-22). After it, the `READ-SEQUENCE.*` / `WRITE-SEQUENCE.*`
tests still failing in the ANSI `streams` chapter (interpreter, suite `ca06bd9`) are argument
checks CLHS makes `type-error`s:

| test | now |
|---|---|
| `READ-SEQUENCE.ERROR.7` (a dotted list `(a . b)`) | stores one element, answers 1 |
| `READ-SEQUENCE.ERROR.8`, `.10` (`:start -1`, `:end -1`) | `%ASET: index -1 out of bounds` / answers 0 |
| `WRITE-SEQUENCE.ERROR.3` (a dotted list) | `WRITE-BYTE expects an integer between 0 and 255` |
| `WRITE-SEQUENCE.ERROR.4`-`.10` (`:start`/`:end` negative, non-integer, symbol) | `SUBSEQ: invalid bounds` / `SUBSEQ expects an integer index` (not `type-error`) or no signal |

(`.ERROR.6 .9 .11 .14-.16` fail on `*MINI-UNIVERSE*`, the aux layer, not on this.)

The shared expansion (`LispMacroExpander.expandReadSequence` / `expandWriteSequence`) is the one
place to add a bounds check that signals `type-error` before the packed / chars / element arms,
on all four backends at once. Check the size cost on the corpus the way `.todo/920` did
(`.kb/read-load-streams.md`, "The stream picks the element") -- every non-narrowed site grows.
