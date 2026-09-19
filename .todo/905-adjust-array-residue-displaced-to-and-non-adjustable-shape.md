# `adjust-array` residue: `:displaced-to`, and the non-adjustable / string shapes

Difficulty: Medium

Split out of `.todo/715` (2026-09-19). The fill-pointer / adjustable / displaced
surface is largely DONE and pinned in `.kb/adjustable-arrays.md` -- but the ANSI
`adjust-array` row is still red on the interpreter. This is the residue, which
`.todo/043` (bit-vectors) and `.todo/180` closed without covering.

## What fails (2026-09-19, interpreter, suite `ca06bd9`, test-level)

`ADJUST-ARRAY` = 96 tests. Two clusters plus a misc one:

- **`adjust-array :displaced-to` (37 of the 96, `ADJUST-ARRAY.10/.12-.16/.34-.37`,
  `.ADJUSTABLE.*`, `.STRING.*`):** `ADJUST-ARRAY: :displaced-to is not supported`.
  `.kb/adjustable-arrays.md` documents the displaced view surface as DONE for the
  cl-utilities `copy-array` shape, but a directly-`adjust-array`-with-`:displaced-to`
  (no `:initial-contents`, with `:displaced-index-offset`) is refused.
- **Non-adjustable shape (the `.STRING.*` `assert (OR (ADJUSTABLE-ARRAY-P A1)
  (EQUAL (ARRAY-DIMENSIONS A1) '(5)))` lines, ~11):** an obvious non-adjustable
  adjustment should keep the array and grow it, not fail.
- **Wrong contents (`ADJUST-ARRAY.3/.4` `got (#(A B C D)) want (#(W X Y Z))`,
  `.ADJUSTABLE.3/.4`, `.STRING.ADJUSTABLE.3/.4`, `.30/.31`):** the adjustment
  resized but did not RETAIN/REPLACE the element contents per `:initial-contents`.
  `ADJUST-ARRAY.STRING.ADJUSTABLE.7` `got (3 "abc") want (4 "abcd")` is the
  string grow-to-copy case.

## Notes

- The displaced view mechanics and the `header.length > 4` / `header slot`
  displacement markers are in `.kb/adjustable-arrays.md` (`.kb` applies to all four
  backends; the pinned test is across interpreter/JVM/WASM-GC, `--no-gc` rejects
  the whole fill-pointer/adjustable surface).
- `.kb/adjustable-arrays.md` already covers `adjustable-array-p`,
  `array-displacement`, `array-displaced-p`; only the `adjust-array` keyword
  handling and the content-retention semantics are missing.
- Measure as a DIFF of failing test NAMES; report fixed AND regressed.
