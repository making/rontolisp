# A character index costs the same wherever it lands

**Invariant: `(char s i)` / `(schar s i)` / `(aref s i)` / `(elt s i)` / `(length s)` /
`(subseq s a b)` are O(1) or amortized O(1) in the index on ALL FOUR backends, in BOTH string
representations -- the immutable runtime string AND the mutable character vector a `make-string`
buffer is. A left-to-right (or right-to-left) scan of one string is LINEAR, never quadratic.**

A cost invariant, not a semantic one. Invisible at small sizes, which is why regressions here
survive: the ci-spec corpus and doc examples use short strings.

## Character vectors: do not render, just read the element
The mutable character vector (`.kb/adjustable-arrays.md`) stores code points, so it has no offset
problem -- but the index sites used to normalize through `_strv` (JVM) / `_charvec_to_str` (WASM),
rendering the WHOLE vector per index.
- **JVM**: each `(char s i)`/`(schar s i)` is ONE call to `_charRef(Object, int) -> int`
  (`JvmStringIndexRuntimeBuilder`): a length-4/-7 slot-0 header reads its element through `_rmGet`
  (displacement walk included, so string views work); anything else takes `_cpoff` + `codePointAt`.
- **WASM**: `_str_char_ref(s, i) -> i32` (`FUNC_STR_CHAR_REF`,
  `WasmStringRuntimeBuilder.buildStrCharRefBody`): `_charvec_p` (O(1) shape test) then `_arr_get`,
  else `_str_char_at`.
- `elt` reaches the same call through `expandElt`'s `stringp` arm; `aref` never rendered. The
  interpreter's character vector IS a mutable `LispString`.
- Unchanged: whole-string consumers (`string=`, case/trim, `concatenate`, `subseq`,
  `write-string`, `intern`, `_equal`/`_hash`/`_print_val`) still render once per CALL.

## Why a character index is not a memory offset
A CHARACTER is a code point ([[characters-code-points]]); no backend stores one per storage unit:

| backend | storage | index i lives at |
| --- | --- | --- |
| interpreter | `int[]` (`LispString`) | slot `i` -- O(1) |
| JVM | UTF-16 `java.lang.String`, quote-framed | code unit `1 + i` UNLESS a surrogate pair precedes |
| WASM | UTF-8 `$str_bytes`, quote-framed ([[wasm-gc-strings]]) | byte `1 + i` UNLESS a multi-byte sequence precedes |

Both compile backends used to walk from the start (`String.offsetByCodePoints(1, i)`;
`_str_char_byte_offset`'s UTF-8 decode). General fix: **decide the translation without walking
where the string's shape allows, and REMEMBER what a walk found so the next index resumes.**
Neither backend changed its storage; on the interpreter the fix was "read the slot".

## WASM: the string carries its own cursor
`TYPE_STRING` (rec-group type 4) gained two `(mut i32)` fields:

```
TYPE_STRING = struct { i32 id, i32 len, (ref null eq) data, (mut i32) ci, (mut i32) cb }
```

= "character `ci` starts at byte `cb`", seeded `(0, 1)` by the only two constructors `_str_build` /
`_str_fresh`, so valid from creation. `WasmStringRuntimeBuilder.buildStrCharByteOffsetBody`:
- **`cb == ci + 1` means the `ci` characters before the cursor are one byte each**, so ANY index at
  or below `ci` is `1 + i`. For an ASCII string that always holds.
- Otherwise the walk starts at whichever of the cursor and the string start is nearer, forwards or
  backwards (one character back is one byte back plus the continuation bytes below it), and the
  cursor follows the index it answered -- one step per character on a scan.
- The cursor is stored ONLY when the walk landed exactly on character `i` (`remaining == 0`); an
  index past the end answers the terminator (`len - 1`) without claiming a character lives there.
  Reaching the general path implies `i > ci || cb != ci + 1`, so a store can never cost the
  single-byte-prefix fact the fast path reads -- no "never move the cursor backwards" case.

`_str_char_count` (every `(length s)` on a string) shares the pair: a cursor on the closing quote
(`cb == len - 1`) HOLDS the count, so a repeated `length` is one compare; otherwise it resumes at
`cb` and parks the cursor on the terminator -- always a forward move, hence always legal, and for a
single-byte string it leaves `cb == ci + 1` at the far end, making every later index O(1).

**Soundness: a string's bytes never change after it is built.** An indexed write rebuilds
(`%schar-set-runtime`, [[string-write-runtime]]) and a character vector is a different
representation, so a cursor cannot go stale. `subseq` reads the same helper twice.

## JVM: prove the string cannot need the walk, then remember the proof
`JvmStringIndexRuntimeBuilder` emits `_cpoff(String, int) -> int` and `_scount(String) -> int`;
every character index and string length reads through them (`JvmCharCompiler.compileChar`,
`JvmArrayRuntimeBuilder._aref1`, `JvmSubseqCompiler`, `JvmLengthRuntimeBuilder`). The proof:

```java
s.codePointCount(1, len - 1) == len - 2      // no surrogate pair anywhere
```

When it holds, character index == code-unit index: offset `1 + i`, count `len - 2`. The probe is
CONSTANT TIME for a LATIN1-backed string (the JDK's `codePointCount` returns the range width
without looking; `offsetByCodePoints` has no such shortcut), so every ASCII/Latin-1 string is O(1)
per index with nothing remembered.

For a wider string the probe walks, so the result is remembered in two plain static fields holding
the last two strings PROVEN free of surrogate pairs. Three load-bearing properties make that safe
under one-virtual-thread-per-request ([[concurrent-served-requests]]): a `java.lang.String` is
immutable; a reference field is written atomically, so a racing reader sees an older string (a
re-probe) and never a torn pair -- which is why neither field is `volatile` and why the memory is a
bare reference rather than a (string, count) pair needing publication as one object; and only the
POSITIVE fact is cached, so there is nothing to invalidate. TWO entries because these walks come in
pairs -- `%string-compare` ([[characters-code-points]]) steps two strings at once.

## Costs and inherited wins
- WASM: **8 bytes per string** for the two fields, and the bigger `_str_char_byte_offset` /
  `_str_char_count` bodies are the whole module size difference (`zlib` +262 bytes at either
  `--optimize` level, `hello_world` +8, `pi_approx` +12, nothing when the shake drops the helpers).
- `subseq`/`replace`/`search`/`position` needed no special-casing: they step with `char` / `aref` /
  the same byte-offset helper. The interpreter's instance was `(replace <array> <string>)`, whose
  per-element `sequenceRef` rebuilt the whole Java `String`; `(replace <string> <string>)` never had
  it (`LispString.replaceInPlace` walks the source once).

## Still not constant
- **WASM, a multi-byte string indexed randomly far from its cursor**: O(min(i, |i - ci|)). The fix
  if it matters is a stride index (a lazily built byte offset every K characters) on the same
  struct, NOT a return to walking from zero.
- **JVM, a string containing a surrogate PAIR** still walks `offsetByCodePoints` per index -- the
  proof is exactly what fails for it. A WASM-style cursor cannot hang off a `java.lang.String`; it
  would need the (string, position) pair published as one immutable object, an allocation per
  advance.
- **JVM, more than two wide strings interleaved** thrash the two-slot memory and re-probe (O(n)) per
  switch. Widen the slot count before anything more clever.

## Pinning tests
- Character vectors: ci-spec `character-vector-index-reads-the-element` (all four backends --
  char/schar/elt/aref agreement on a `make-string` buffer, non-ASCII elements, a displaced string
  view, whole-vs-chunked scan cost), plus
  `JvmLispCompilerTest.compileACharacterIndexIntoACharacterVectorReadsTheElement` /
  `#compileACharacterVectorIndexAgreesAcrossTheFourSpellings` and
  `WasmLispCompilerIntegrationTest.aCharacterIndexIntoACharacterVectorDoesNotRenderTheVector` /
  `#aCharacterVectorIndexAgreesAcrossTheFourSpellings`.
- ci-spec `character-index-is-not-linear-in-the-index` (all four backends): forward / backward /
  jumping access orders over one mixed ASCII / two-byte / three-byte / astral string (the cursor may
  not change what an index answers), then the cost as `SCAN-FLAT` / `LENGTH-FLAT` -- 131,072
  characters as ONE string against the same characters in 1,024-character chunks. Comparing the two
  halves rather than a wall-clock constant is what makes the bound machine-independent.
- `JvmLispCompilerTest.compileACharacterIndexDoesNotWalkFromTheStartOfTheString` /
  `#compileACharacterIndexDoesNotWalkForANonLatin1String` (the second over Hiragana, so the LATIN1
  shortcut cannot carry it).
- `WasmLispCompilerIntegrationTest.aCharacterIndexDoesNotDecodeFromTheStartOfTheString` /
  `#aCharacterIndexDoesNotDecodeFromTheStartOfAMultiByteString` /
  `#aStringLengthDoesNotRecountTheWholeStringOnEveryCall`.
- Behavior stays pinned by ci-spec `code-point-characters-beyond-ascii`,
  `compileStringIndexingByCodePoint`, and the string cases of `LispEvaluatorTest` /
  `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`.

## Related
[[characters-code-points]], [[wasm-gc-strings]], [[length-runtime]] (the shared `length` dispatch
calling the count helpers), [[string-write-runtime]] (why an indexed write cannot invalidate a
cursor).
