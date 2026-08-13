# A character index costs the same wherever it lands

**Invariant: `(char s i)` / `(schar s i)` / `(aref s i)` / `(elt s i)` / `(length s)` /
`(subseq s a b)` are O(1) or amortized O(1) in the index on ALL FOUR backends. A
left-to-right (or right-to-left) scan of one string is LINEAR in its length, never
quadratic.**

This is a cost invariant, not a semantic one -- the answers were always right. What was
wrong is that they were paid for by walking from the first byte on every single access,
so `(dotimes (i (length s)) (char s i))` was O(n^2) on every compile backend
(`.todo/185`). It is not visible at small sizes, which is exactly why it survived: the
ci-spec corpus and the doc examples all use short strings.

## Why a character index is not a memory offset

A rontolisp CHARACTER is a Unicode CODE POINT ([[characters-code-points]]), and no
backend stores its strings one code point per unit of storage:

| backend | storage | index i lives at |
| --- | --- | --- |
| interpreter | `int[]`, one code point per slot (`LispString`) | slot `i` -- O(1) |
| JVM | UTF-16 `java.lang.String`, quote-framed | code unit `1 + i` UNLESS a surrogate pair precedes it |
| WASM (both) | UTF-8 `$str_bytes` array, quote-framed ([[wasm-gc-strings]]) | byte `1 + i` UNLESS a multi-byte sequence precedes it |

Both compile backends therefore have to translate, and the translation used to be a walk
from the start: `String.offsetByCodePoints(1, i)` on the JVM, `_str_char_byte_offset`'s
UTF-8 decode on WASM. The interpreter's version of the same mistake was different in
shape and worse in constant -- it rebuilt the whole Java `String` from the `int[]` on
every access and then walked THAT -- and `charRef` was fixed first (`.todo/184`), with
`sequenceRef` (the per-element read behind `replace`) following in `.todo/185`. On the
interpreter the fix is simply "read the slot"; there is nothing to remember.

The fix on both compile backends is the same shape: **decide the translation without
walking whenever the string's own shape allows it, and REMEMBER what a walk found so the
next index resumes instead of restarting.** Neither backend changes its storage.

## WASM: the string carries its own cursor

`TYPE_STRING` gained two `(mut i32)` fields (`WasmLispCompiler`, rec-group type 4):

```
TYPE_STRING = struct { i32 id, i32 len, (ref null eq) data, (mut i32) ci, (mut i32) cb }
```

The pair reads **"character `ci` starts at byte `cb`"**, and it is seeded `(0, 1)` by the
only two constructors, `_str_build` / `_str_fresh` -- character 0 always starts right
after the opening quote, so the pair is valid from the moment a string exists. Two facts
fall out of it, and they are the whole optimization
(`WasmStringRuntimeBuilder.buildStrCharByteOffsetBody`):

- **`cb == ci + 1` means the `ci` characters before the cursor are one byte each**, so
  ANY index at or below `ci` is `1 + i`. For a single-byte (ASCII) string that condition
  always holds, so once the cursor has been anywhere past `i`, index `i` is a compare and
  an add -- at any position, in any order.
- **Otherwise the walk starts at whichever of the cursor and the string start is nearer**,
  forwards or backwards (one character back is one byte back plus the continuation bytes
  below it), and the cursor follows the index it answered. A scan in either direction
  therefore costs one step per character.

The cursor is stored ONLY when the walk landed exactly on character `i`
(`remaining == 0`); an index past the end answers the terminator (`len - 1`, unchanged)
without claiming a character lives there. Reaching the general path at all implies
`i > ci || cb != ci + 1`, so a store can never cost the single-byte-prefix fact the fast
path reads -- there is no "do not move the cursor backwards" special case to get wrong.

`_str_char_count` (every `(length s)` on a string) shares the pair: a cursor already on
the closing quote (`cb == len - 1`) HOLDS the count, so a repeated `length` is one
compare; otherwise it resumes the walk at `cb` and parks the cursor on the terminator.
That is a forward move, so it is always legal -- and for a single-byte string it leaves
`cb == ci + 1` at the far end, which is what makes every subsequent index O(1) with no
scan of its own. `(length s)` in a loop head is the common way a program pays for this,
and it now pays once.

**Soundness: a string's bytes never change after it is built.** An indexed write rebuilds
(`%schar-set-runtime`, [[string-write-runtime]]) and a mutable character vector is a
different representation that normalizes to a fresh string, so a cursor cannot go stale.
`subseq` reads the same helper twice and therefore also stops walking from byte 0.

## JVM: prove the string cannot need the walk, then remember the proof

`JvmStringIndexRuntimeBuilder` emits two helpers, `_cpoff(String, int) -> int` and
`_scount(String) -> int`, and every character index and every string length reads through
them (`JvmCharCompiler.compileChar`, `JvmArrayRuntimeBuilder._aref1`,
`JvmSubseqCompiler`, `JvmLengthRuntimeBuilder`). The proof is one comparison:

```java
s.codePointCount(1, len - 1) == len - 2      // no surrogate pair anywhere
```

and when it holds, character index == code-unit index, so the offset is `1 + i` and the
count is `len - 2`. The probe itself is CONSTANT TIME for a LATIN1-backed string -- the
JDK's `codePointCount` returns the range width without looking at it, and
`offsetByCodePoints` has no such shortcut -- so every ASCII and Latin-1 string is O(1)
per index with nothing remembered at all.

For a wider string the probe walks, so the result is remembered in two plain static
fields holding the last two strings PROVEN free of surrogate pairs. Three properties make
that memory safe under the one-virtual-thread-per-request rule
([[concurrent-served-requests]]), and all three are load-bearing:

- a `java.lang.String` is immutable, so a remembered fact can never go stale;
- a reference field is written atomically, so a racing reader sees an older string (a
  re-probe, costing nothing but time) and never a torn pair -- which is why neither field
  is `volatile`, and why the memory is a bare reference rather than a (string, count)
  pair that WOULD have to be published as one object;
- only the POSITIVE fact is cached, so there is nothing to invalidate.

Two entries rather than one because these walks come in pairs: `%string-compare`
([[characters-code-points]]) steps two strings at once and a one-entry memory would
thrash between them.

## Measured

One 49,152-character ASCII string, 2026-08-13, one machine, wasmtime 47 (ms):

| | scan (every index once) | 20,000 reads at index 40,000 | 20,000 `(length s)` |
| --- | ---: | ---: | ---: |
| interpreter | 147 -> 147 | 74 -> 75 | 24 -> 25 |
| JVM | 621 -> **31** | 293 -> **14** | 6 -> 7 |
| WASM Preview 1 | 2,559 -> **3** | 1,575 -> **2** | 1,530 -> **0** |
| `--component` | 2,487 -> **3** | 1,585 -> **2** | 1,522 -> **1** |

The interpreter row is the reference shape: it was already flat and it does not move. The
JVM's `length` was already flat too (LATIN1 `codePointCount`), which is why that column
does not move there either -- the JVM's problem was the INDEX, and WASM's was both.

Costs, both bounded: the two struct fields are **8 bytes per string on the wasm-GC heap**,
and the bigger `_str_char_byte_offset` / `_str_char_count` bodies are the whole module
size difference -- `zlib` +262 bytes at either `--optimize` level (95,444 -> 95,706 and
72,480 -> 72,742), `hello_world` +8, `pi_approx` +12, and nothing at all for a program
whose shake drops the helpers.

## Everything above the primitive inherited it

`.todo/185` named `subseq` / `replace` / `search` as a non-goal, on the theory that each
carries its own walk. None of them does: they step with `char` / `aref` / the same byte
offset helper, so fixing the primitive fixed them, and nothing in them was special-cased.
Same string as above (ms), before -> after:

| | JVM | WASM Preview 1 |
| --- | ---: | ---: |
| 2,000 `subseq` slices across the string | 74 -> **8** | 202 -> **1** |
| `(position #\@ s)`, a full miss | 583 -> **49** | 2,713 -> **7** |
| `(position #\b s :from-end t)` | 524 -> **28** | 2,609 -> **7** |
| `(replace <20,000-char string> s)` | 85 -> **13** | 437 -> **2** |

The interpreter's own instance of this was `(replace <array> <string>)`, whose per-element
`sequenceRef` rebuilt the whole Java `String` and walked it: **1,132 ms -> 4 ms** over
20,000 elements. `(replace <string> <string>)` never had it (`LispString.replaceInPlace`
walks the source once).

## What is still not constant, and when to care

- **WASM, a multi-byte string indexed randomly far from its cursor** is still
  O(min(i, |i - ci|)) -- the cursor answers scans, not arbitrary jumps. If that ever
  shows up, the answer is a stride index (a lazily built byte offset every K characters)
  hung off the same struct, NOT a return to walking from zero.
- **JVM, a string containing a surrogate PAIR** (astral characters) still walks
  `offsetByCodePoints` on every index, because the proof above is exactly what fails for
  it. A cursor like the WASM one cannot be hung off a `java.lang.String`; it would need
  the (string, position) pair published as one immutable object, which costs an
  allocation per advance. Worth doing only for a program that scans astral text.
- **JVM, more than two wide strings interleaved** thrash the two-slot memory and re-probe
  (O(n)) per switch. Widen the slot count before anything more clever.

## Pinning tests

- `src/test/resources/ci-spec.yaml` -- `character-index-is-not-linear-in-the-index`, run
  on all four backends by `CiSpecE2eTest`: the forward / backward / jumping access orders
  over one mixed ASCII / two-byte / three-byte / astral string (the cursor may not change
  what an index answers), then the cost itself as `SCAN-FLAT` / `LENGTH-FLAT` -- scanning
  131,072 characters as ONE string against the same characters in 1,024-character chunks.
  Comparing the two halves rather than a wall-clock constant is what makes the bound
  machine-independent; before the fix the whole-string half was 64x the chunked one.
- `JvmLispCompilerTest.compileACharacterIndexDoesNotWalkFromTheStartOfTheString` and
  `#compileACharacterIndexDoesNotWalkForANonLatin1String` -- the same comparison per
  backend, the second over Hiragana so the LATIN1 shortcut cannot carry it.
- `WasmLispCompilerIntegrationTest.aCharacterIndexDoesNotDecodeFromTheStartOfTheString`,
  `#aCharacterIndexDoesNotDecodeFromTheStartOfAMultiByteString` and
  `#aStringLengthDoesNotRecountTheWholeStringOnEveryCall`.
- The behavior is pinned where it already was: the `code-point-characters-beyond-ascii`
  ci-spec case, `compileStringIndexingByCodePoint`, and the string cases of
  `LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`.

## Related

- [[characters-code-points]] -- what a CHARACTER is, and why the index is by code point.
- [[wasm-gc-strings]] -- the UTF-8 byte model the WASM cursor sits on.
- [[length-runtime]] -- the shared `length` dispatch that calls the count helpers.
- [[string-write-runtime]] -- why an indexed write cannot invalidate a cursor.
