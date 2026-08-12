# `(char s i)` costs O(i) on all three compile backends, so any left-to-right string scan is quadratic

Found while closing `.todo/184`. That item assumed the uax-15 table build was
dominated by hash writes and by how densely the derived data was encoded. It is
neither: **93% (interpreter) / 86% (component) of the whole uax-15 load was one
primitive**, `(char s i)`, and the scan that called it was quadratic because a
single character access is linear in the string.

The interpreter half is FIXED (`eval/Environment.charRef` now indexes the
`int[]` slot instead of rebuilding the whole Java `String` per call, then
counting its code points, then walking to the index -- three linear passes for
one character). The three compile backends are not, and the cost there is
structural, not a slip:

- **JVM** (`codegen/jvm/JvmCharCompiler`, `JvmArrayRuntimeBuilder`,
  `JvmSubseqCompiler`, `JvmLengthRuntimeBuilder`): a string is a Java `String`
  and the character index is turned into a UTF-16 code-unit offset with
  `String.offsetByCodePoints(1, i)`, which walks. `length` is
  `codePointCount(1, len-1)`, which walks the whole string.
- **wasm-GC, both backends** (`codegen/wasm/WasmStringRuntimeBuilder`
  `_str_char_at` / `_str_char_count` / `_str_char_byte_offset`): the byte data is
  UTF-8 and the accessors decode forward from byte 0 (`.kb/wasm-gc-strings.md`).

## Measurement (2026-07-26, native binary + wasmtime 46.0.1, warm)

20,000 accesses at a FIXED index into one 48,000-character string literal, plus
one forward `dotimes` scan of the same string (ms):

| | index 0 | index 24,000 | index 47,999 | forward scan |
| --- | --- | --- | --- | --- |
| interpreter (after the `charRef` fix) | 10 | 10 | 10 | **23** |
| interpreter (before) | 632 | 774 | 928 | ~1000 |
| JVM | 5 | 96 | 147 | **195** |
| wasm Preview 1 | 1 | 435 | 867 | **1045** |
| `--component` | 1 | 495 | 817 | **981** |

The interpreter is now flat in the index; the three compile paths track it
linearly. A forward scan is therefore O(n^2) on them: 4x the length costs
10-16x the time, identically for a single string literal, a
`(concatenate 'string ...)` rope and a `copy-seq` of one, and identically for
`map nil` as for indexed access.

## Why it matters beyond the table that found it

Every program that walks a long string pays this -- a hand-written parser, a
tokenizer, a `loop for i below (length s)` over a file's contents. It is not
visible at small sizes, which is exactly why it survived: the ci-spec corpus and
the doc examples all use short strings.

`.todo/184` worked around it inside its own emitter (the derived runs are now a
quoted list of 1,000-character chunks, cut between integers, so each chunk's scan
is bounded). Over a 55,811-character run that cut the scan from 1439 to 90 ms on a
component, 1328 to 90 on WASM Preview 1 and 280 to 27 on the JVM -- and from 68 to
67 ms on the interpreter, i.e. nothing, because the interpreter is already O(1)
per access. That workaround belongs to that one emitter; it does nothing for user
code.

### Third sighting: it makes every Cloudflare Worker quadratic in body size (2026-08-13)

A host-driven reactor hands the request over as ONE JSON string with the body
inside it, so `json-parse` scans a body-sized string -- and `json-parse` is
`(char s j)` in a loop. `examples/cloudflare-workers/httpbin` unedited
(`--no-wasi --optimize=size`, one request per instance):

| body | `handle-request` |
| --- | --- |
| 4 KiB | 24 ms |
| 16 KiB | 252 ms |
| 64 KiB | 3453 ms |
| 256 KiB | **54701 ms** |

Isolated on the same module: the `:string` boundary itself is FLAT (256 KiB
crosses in 1.0 ms), and `json-parse` alone is 64.6 / 857.9 / 13188.5 ms at
16K / 64K / 256K. The JVM compile path has the same shape (28 / 325 / 5238 ms);
the interpreter, whose `json-parse` is Java, is linear (51 / 65 / 218 ms) -- so
this is the walk, not the parser's algorithm. `.todo/341` changes the envelope
so a body never rides inside JSON again, which lowers the exposure but not the
shape: any user program parsing a large JSON string on a compile backend still
pays it.

## What to do

Per backend, make a character index O(1) or amortized O(1):

- **wasm-GC**: the honest fix is a code-point-per-slot representation for
  strings, as the interpreter already has -- but the UTF-8 byte model is
  load-bearing for the export boundary and for `_charvec_to_str`
  (`.kb/wasm-gc-strings.md`), so read that file first. A cheaper intermediate:
  an ASCII fast path (a string whose byte length equals its character count can
  index directly), which covers essentially every generated data payload and most
  user parsing, with the walk kept for the general case. Measure both before
  choosing -- the fast path is a few instructions and no representation change.
- **JVM**: the same ASCII/BMP fast path (`s.length() - 2 == charCount` implies
  code unit == character), falling back to `offsetByCodePoints`. `length` should
  cache or fast-path `codePointCount` too.
- Whatever is chosen must keep the four-backend character-index contract intact:
  indexing is BY CODE POINT, and a supplementary code point is ONE character
  (`.kb/characters-code-points.md`, pinned by `Uax15E2eTest`'s non-BMP
  round-trip and the `interp-string-int-array` work).

Non-goal for a first pass: `subseq`, `replace` and `search` have the same walk in
them. Fix `char`/`aref`/`length` first and re-measure -- the scan shapes above are
what the cost actually lives in.

## Second, independent idea from the same measurement

`uax-15::*unicode-letters*` is ~127,000 hash entries (21,765 data-derived plus
nine hardcoded CJK/Hangul/Tangut range loops covering ~105,000 codepoints) built
for ONE consumer, `unicode-letter-p`, which only ever looks at truthiness. It is
now lazy, so a program that does not call it pays nothing -- but `postmodern`'s
`util.lisp` calls it twice, and for that program the table is 132 ms
(interpreter) / 148 ms (component) and ~127k live entries, which is exactly the
wasm-GC live-set penalty measured as Finding 2 of the item that preceded 184.
`Uax15Tables.ranges()` already produces sorted inclusive range pairs; replacing
the table with a binary search over them inside `unicode-letter-p` (the same
`replaceForm` shape `get-illegal-char-list` already uses, docstring kept verbatim)
would make the cost ~0 for callers AND non-callers. Deliberately not done in 184:
that item was about laziness, letters turned out to be 7% of the load, and the
change widens the rewrite's behavior surface, so it deserves its own measurement.
