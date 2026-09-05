# A character index costs the same wherever it lands

**Invariant: `(char s i)` / `(schar s i)` / `(aref s i)` / `(elt s i)` / `(length s)` /
`(subseq s a b)` are O(1) or amortized O(1) in the index on ALL FOUR backends, in BOTH
string representations -- the immutable runtime string AND the mutable character vector a
`make-string` buffer is. A left-to-right (or right-to-left) scan of one string is LINEAR,
never quadratic.** A cost invariant, not a semantic one; invisible at the sizes ci-spec
and the doc examples use, which is why regressions here survive.

A CHARACTER is a code point (`.kb/characters-code-points.md`) and no backend stores one
per storage unit: interpreter `int[]` (`LispString`, slot `i`); JVM UTF-16
`java.lang.String`, quote-framed, character `i` at code unit `1 + i` unless a surrogate
pair precedes; WASM UTF-8 `$str_bytes`, quote-framed (`.kb/wasm-gc-strings.md`), byte
`1 + i` unless a multi-byte sequence precedes. General fix: **decide the translation
without walking where the shape allows, and REMEMBER what a walk found.**

## Character vectors: do not render, just read the element
The mutable character vector (`.kb/adjustable-arrays.md`) stores code points; index sites
must not normalize through `_strv` (JVM) / `_charvec_to_str` (WASM), which renders the
WHOLE vector per index.
- **JVM**: one call to `_charRef(Object, int) -> int` (`JvmStringIndexRuntimeBuilder`),
  reading through `_rmGet` (displacement walk, so string views work) or `_cpoff`.
- **WASM**: `_str_char_ref(s, i) -> i32` (`FUNC_STR_CHAR_REF`,
  `WasmStringRuntimeBuilder.buildStrCharRefBody`).
- `elt` reaches it through `expandElt`'s `stringp` arm; `aref` never rendered. Whole-string
  consumers (`string=`, `concatenate`, `write-string`, `_equal`/`_hash`/`_print_val`)
  still render once per CALL.

## WASM: the string carries its own cursor
`TYPE_STRING` (rec-group type 4) gained two `(mut i32)` fields:

    struct { i32 id, i32 len, (ref null eq) data, (mut i32) ci, (mut i32) cb }

= "character `ci` starts at byte `cb`", seeded `(0, 1)` by the only two constructors
`_str_build` / `_str_fresh`. `WasmStringRuntimeBuilder.buildStrCharByteOffsetBody`:
- **`cb == ci + 1` means the `ci` characters before the cursor are one byte each**, so any
  index at or below `ci` is `1 + i` -- always true for ASCII. Otherwise the walk starts at
  whichever of the cursor and the string start is nearer, and the cursor follows.
- The cursor is stored ONLY when the walk landed exactly on character `i`
  (`remaining == 0`), so a store can never cost the fast path's single-byte-prefix fact.
- `_str_char_count` (every `(length s)`) shares the pair: a cursor on the closing quote
  (`cb == len - 1`) HOLDS the count, so a repeated `length` is one compare.
- **Soundness: a string's bytes never change after it is built** -- an indexed write
  rebuilds (`.kb/string-write-runtime.md`), so a cursor cannot go stale.

## JVM: prove the string cannot need the walk, then remember the proof
`JvmStringIndexRuntimeBuilder` emits `_cpoff(String, int) -> int` and
`_scount(String) -> int`; every character index and string length reads through them. The
proof:

    s.codePointCount(1, len - 1) == len - 2      // no surrogate pair anywhere

When it holds, offset is `1 + i` and count `len - 2`. The probe is CONSTANT TIME for a
LATIN1-backed string, so every ASCII/Latin-1 string is O(1) with nothing remembered. For a
wider string the result is remembered in two plain static fields holding the last two
strings PROVEN surrogate-free -- safe under one-virtual-thread-per-request
(`.kb/concurrent-served-requests.md`) because a `String` is immutable, a reference field is
written atomically (hence not `volatile`, and a bare reference rather than a pair needing
publication), and only the POSITIVE fact is cached. TWO entries because `%string-compare`
steps two strings at once.

## Costs and what is still not constant
- WASM: **8 bytes per string** plus the bigger helper bodies (`zlib` +262 bytes at either
  `--optimize` level); `subseq`/`replace`/`search`/`position` needed no special-casing.
- **WASM, a multi-byte string indexed randomly far from its cursor**: O(min(i, |i - ci|)).
  The fix if it matters is a stride index on the same struct, NOT walking from zero.
- **JVM, a string containing a surrogate PAIR** still walks `offsetByCodePoints` per index;
  a WASM-style cursor cannot hang off a `java.lang.String`.
- **JVM, more than two wide strings interleaved** thrash the two-slot memory. Widen the
  slot count before anything more clever.

## Pinning tests
- ci-spec `character-vector-index-reads-the-element`,
  `character-index-is-not-linear-in-the-index` (all four backends): forward/backward/jumping
  orders over one mixed ASCII / 2-byte / 3-byte / astral string, then cost as `SCAN-FLAT` /
  `LENGTH-FLAT` -- 131,072 characters as ONE string against 1,024-character chunks.
  Comparing the two halves is what makes the bound machine-independent.
- `JvmLispCompilerTest.compileACharacterIndexDoesNotWalk{FromTheStartOfTheString,ForANonLatin1String}`
  (the second over Hiragana, so the LATIN1 shortcut cannot carry it),
  `#compileACharacterIndexIntoACharacterVectorReadsTheElement`.
- `WasmLispCompilerIntegrationTest.aCharacterIndexDoesNotDecodeFromTheStartOf{TheString,AMultiByteString}`,
  `#aStringLengthDoesNotRecountTheWholeStringOnEveryCall`,
  `#aCharacterIndexIntoACharacterVectorDoesNotRenderTheVector`.

Related: `.kb/characters-code-points.md`, `.kb/wasm-gc-strings.md`,
`.kb/length-runtime.md`, `.kb/string-write-runtime.md`.
