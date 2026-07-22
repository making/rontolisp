# Interpreter mutable-string storage: char[] -> int[] (code point per slot)

After the wider code-point work in todo 153 every read path on every backend
indexes strings by CODE POINT. Mutation is symmetric on the JVM and WASM
(their char vectors store the runtime CHARACTER shape -- `int[]{cp}` on JVM,
`TYPE_CHAR` on WASM -- one code point per slot, so `(setf (schar s i) (code-char
128512))` succeeds and prints the glyph). The interpreter still stores
`char[]` (UTF-16 code units) in `LispString.chars`, so `%schar-set`
(`Environment.storeStringChar`) rejects a supplementary code point with
`cannot store a supplementary character`.

The divergence is auditable but violates "all four backends" byte-identical
output on the mutation side. This item widens the interpreter storage so the
interpreter accepts a supplementary code point in exactly one indexed slot,
matching the JVM and WASM already.

## Plan

Switch `LispString.chars` from `char[]` to `int[]` (Unicode code point per
slot). Every existing accessor becomes character-visible, and mutation
symmetry with the JVM/WASM is restored.

- `LispString.chars: char[]` -> `int[]`, `fillPointer` / `capacity()` stay
  in slot-count (= code-point-count) units. `codePointCount()` /
  `codePointAt(int)` become trivial index / lookups.
- `LispString(String)` seeds `chars` via `String.codePoints().toArray()`, so
  the constructor's length is the CODE-POINT length. Every callsite that
  passed a Java `String` builds one code-point-per-slot mutable string.
- `LispString.value()` reassembles via `new String(chars, 0, len)`
  (String has an `int[] codePoints` constructor -- fine for supplementary
  values).
- `setCharAt(int, int)` (rename or overload -- `char` -> `int`), same for
  `vectorPushExtend(int)` / `replaceInPlace`. Every interpreter caller
  hands over a full code point instead of a Java `char`.
- `Environment.storeStringChar` -- drop the `Character.isBmpCodePoint`
  reject; every code point stores in one slot now.
- Audit every consumer of `LispString.charAt(int)` in the interpreter
  (search: `str.charAt`, `chars[i]`) -- they now receive an `int` code
  point, not a `char`. Most already treat it as `int` for comparison; the
  handful that assign back into a `char` local (LispReader source scans,
  IO column tracking, ...) need widening.

## Related widenings to keep in the same pass

- `LispString.replaceInPlace(int, String, int, int)` -- the inner
  `source.charAt(s)` copy currently narrows to a Java `char`. Reword to walk
  the source by code point so `(replace target-mutable-string astral-source)`
  round-trips.
- The interpreter `read-char` path already returns a raw
  `BufferedReader.read()` int; combining a surrogate pair on the fly would
  finish the "read-char returns a supplementary code point" story too. This
  is a smaller ancillary fix worth doing while the pattern is fresh.

## Non-goals

- No change to `LispString`'s external API surface for the compiler / eval
  runtime (`length` / `charAt` continue to exist under those names but their
  contract widens to code-point units).
- No representation change on the JVM char vector -- it already stores
  `int[]{cp}` per slot.
- No representation change on WASM (unchanged; storage is one `TYPE_CHAR`
  per slot).

## Verification

- Add a mutation-round-trip case to `.kb/characters-code-points.md` and to
  `ci-spec.yaml` (`(setf (schar s 0) (code-char 128512))` prints as its
  glyph on every backend).
- Extend `LispEvaluatorTest.evalStringIndexingByCodePoint` with a mutation
  round-trip on a mutable string of `element-type 'character`.
- Every existing test still passes without adjustment: the API widens, no
  contract narrows.
