# Bit-vector preservation across sequence ops (`subseq`, computed `coerce`, `map`, `make-sequence`, printing, `class-of`)

Difficulty: Medium

**Status:** open (filed 2026-09-16 when closing `.todo/043`). The bit-vector
representation itself is done: a bit vector is the general boxed array stamped with
the remembered element type `bit` (`.kb/array-literals.md`), recognized by
`bit-vector-p`/`simple-bit-vector-p`/`typep`/`type-of`/`subtypep` on all four backends,
with the eleven `bit-*` operators, `bit`/`sbit`, `logbitp`, `array-in-bounds-p`,
`arrayp`, `fill` and the `simple-*-p` predicates (`.todo/043` pins:
`bit-vectors-and-bit-ops` ci-spec case plus the three engine twins).

What REMAINS is keeping the stamp where a bit vector flows through:

1. `subseq` (and `%array-alike`, i.e. the `sort`/`remove`/`substitute` rebuilds) of a
   bit vector answers an unstamped `t` vector on every backend -- the interpreter's
   general arm builds `new LispArray(dims, copy)` (stamp dropped), the JVM/WASM
   `%array-alike` general paths stamp marker 0. `(bit-vector-p (subseq #*1010 0 2))`
   is nil. Pre-existing shape (a rank-n character array's stamp drops the same way),
   but CL answers a bit vector.
2. `coerce` with a COMPUTED compound designator holding a bit element type
   (`(let ((s '(vector bit))) (coerce x s))`) takes the `t` vector arm. Literal
   spellings (atomic and compound) and computed ATOMIC spellings stamp since 043;
   only the computed-compound element read is missing (`expandComputedCoerce`).
3. `map` and `make-sequence` with a bit-vector result type signal a clear
   "unsupported result type" error instead of building one. Acceptable posture
   (an error, never a wrong answer), but CL builds a bit vector.
4. Printing: a bit vector prints as the general vector (`#(0 1)`), not `#*01`, so a
   printed bit vector does not read back as one. Same class as the rank-n character
   array print, which also loses its stamp on the read-back.
5. `class-of` answers `vector` for a bit vector (the interior-class design,
   `.kb/clos.md`); `(class-of #*01)` is not `bit-vector`.

## Approach

- `subseq`/`%array-alike`: thread the stamp through the general arms (interpreter
  `LispArray` copy, JVM header slot 4, WASM meta marker) the way `adjust-array`
  already does with `%array-adopt-element-type`.
- Computed `coerce`: read the element out of the held compound specifier value in
  `expandComputedCoerce` (mirror of the literal `coerceResultIsBitVector`).
- `map`/`make-sequence`: route the bit family through the same bit build the
  `coerce` literal arm uses (`coerceListToBitVector` shape).
- Printing: rank-1 `bit`-stamped arrays print `#*` when every element is 0/1
  (needs the non-bit-element fallback: `make-array` never validates stores).
- `class-of`: answer `bit-vector` would move the name out of
  `FIND_CLASS_ONLY_CLASS_NAMES` -- check the CLOS dispatch tables first.

## Related

- `.todo/043` (closed 2026-09-16; the representation + operator surface)
- `.todo/715` (the ANSI billing: bit-array rows that stay red live here now)
- `.todo/807` (`simple-bit-vector-p` remeasure row)
