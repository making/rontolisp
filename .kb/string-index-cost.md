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

## JVM: two shapes, and a memory that keeps the expensive one
`JvmStringIndexRuntimeBuilder` emits `_cpoff(String, int) -> int` and `_scount(String) -> int`,
which every character index and string length reads through, plus `_cpidx(String) -> int[]`, the
slow half they share.

**Flat** -- no surrogate pair anywhere -- is decided by one comparison:

    s.codePointCount(1, len - 1) == len - 2      // no surrogate pair anywhere

When it holds, offset is `1 + i` and count `len - 2`. The probe is CONSTANT TIME for a
LATIN1-backed string, so every ASCII/Latin-1 string is O(1) with nothing remembered; for a wider
one the fact is remembered in two plain static fields (`_cpsimple0/1`).

**Wide** -- one surrogate pair is enough -- has no such arithmetic and used to fall back to
`offsetByCodePoints(1, i)` on EVERY index with nothing remembered, which is quadratic in a string
that is otherwise pure ASCII. It gets a BREAKPOINT TABLE now, built in one pass: `t[0]` is the
character count and `t[1 + k]` the code-unit offset of character `k << 5`, so an index walks at
most 31 characters from the nearest breakpoint and costs the same wherever it lands. One int per
32 characters.

**How each pair is published.** The flat pair is plain: a `String` is immutable so the fact cannot
go stale, a reference field is written atomically, only the POSITIVE fact is cached, and a miss
costs a re-probe -- hence no `volatile`, safe under one-virtual-thread-per-request
([[concurrent-served-requests]]). The wide pair (`_cpwide0/1`) IS volatile: a slot is an
`Object[]{String, int[]}`, and a plain store publishes neither the second element nor the table's
contents, so a racing reader could match the string and then read an unwritten offset -- an answer
pointing inside the framing quote. The release fence is the publication.

**The victim is the SHORTER slot, in both pairs.** What the memory buys is the walk it skips, and
that cost is the string's LENGTH, not how recently it was touched. Under the old "shift slot0 into
slot1" rule any two short strings touched between two indexes evicted a long one -- and a JSON
parse is precisely that shape, one fresh short string cut out per token interleaved with every
index into the document, so the document was re-proven at O(n) every few characters. TWO entries in
each pair because the walks come in pairs (`%string-compare` steps two strings at once).

**The assumption, stated in the open: length is a PROXY for value.** A large string indexed ONCE
holds its slot against a smaller one indexed constantly -- index a 10 MB string once, then
alternate hot indexing between two 1 MB strings, and the cold 10 MB entry never leaves while the
two hot ones fight over the remaining slot; recency would have aged it out. The shape is reachable,
the regression is bounded by the two slots, and two slots do not justify an aging policy. What to
widen for is more than two HOT strings at once, and the state to add then is a hit count per slot,
not a timestamp.

Measured 2026-09-05 (JDK 25, this rule against the previous one, same tree):
- `rontolisp:json-parse` over the real Qwen3.5-0.8B `tokenizer.json` -- 12,807,982 bytes,
  10,769,328 characters, 745,102 above U+00FF and NONE astral, so the document is flat and
  UTF16-backed -- on the JVM class output: did not finish (still inside `vocab` at 180 s, with
  `StringUTF16.codePointCount` under `_cpoff` under `%JSON-SKIP-WS` at every sample, the frames
  `.todo/690` reported) -> **525 ms**. The text has to arrive as a `java.lang.String` to be the
  subject at all: `rontolisp:octets-to-string` produces one, while `uiop:read-file-string` and
  `concatenate` produce a character vector, which reads its element and never probes.
- One emoji in front of 30,720 digits, scanned whole against the same characters in 481-character
  pieces: 630 ms / 14 ms -> 9 ms / 5 ms.

**What the 340x is and is not.** It is a claim about `rontolisp:json-parse`, not about loading a
model. `examples/llm/llm.lisp`'s checkpoint load did NOT get faster: re-measured on the
shipped tree it is 9.0-9.8 s from GGUF and 8.9-9.1 s from safetensors, the same as before within
noise. That file carried its own byte-level JSON reader **because `json-parse` could not finish**,
so it never paid the cost and had nothing to recover. Deleting those 180 lines removed a workaround
at equal speed (425 ms against 447 ms), which is a maintenance win and not a throughput one.

Four separate things landed here and only the first two are speed for an existing caller:

1. the interpreter's `subseq` no longer renders the whole buffer per call -- general, and the one
   defect nothing anywhere had routed around;
2. a surrogate-bearing string is indexed in constant time -- the 45x above;
3. `rontolisp:json-parse` becomes usable on a large file **at all**, so nothing has to route around
   it in future -- the value is to callers that do not exist yet;
4. one existing workaround deleted at parity.

The generalisation to avoid, because it was made and was wrong: a mechanism being fixed does not
mean its known callers were paying for it. Check which callers were actually on the slow path
before claiming a speedup for them. **And read a workaround as evidence** -- the byte reader sitting
in the tree was a recorded fact about `json-parse` that nobody had read as one.

## Cutting a piece out is an index too
`(subseq s a b)` may not cost the STRING's length either. On the interpreter it copies the slice
straight out of the code-point buffer (`LispString.subsequence`); it used to render the whole
buffer into a `java.lang.String` through `value()` and then `substring` it, which cost O(length of
s) whatever the slice's width -- so the same JSON parse was quadratic on the interpreter for a
reason of its own. Synthetic tokenizer-shaped JSON, 2026-09-05: 88k characters 1.5 s, 178k 4.8 s,
378k 19 s, 778k 77 s (clean N^2) -> 0.45 / 0.53 / 0.72 / 1.58 s, and the real 10,769,328-character
`tokenizer.json` **15.7 s** (against an N^2 extrapolation of about four hours, which is why the
report saw no first timing line in seven minutes).

Whole-string consumers (`string=`, `concatenate`, `write-string`, `_equal`/`_hash`/`_print_val`)
still render once per CALL, which is their own cost and not an index's. **Separate mechanism, not
fixed here**: ACCUMULATING a file into a string is quadratic in its own right on the compile path
-- `uiop:read-file-string`'s chunked `concatenate` and the `apply #'concatenate 'string` over
`read-line` results both re-copy the accumulator per chunk, and neither reaches `json-parse` at all
on a 12.8 MB file. Tracked as `.todo/704`.

## Costs and what is still not constant
- WASM: **8 bytes per string** plus the bigger helper bodies (`zlib` +262 bytes at either
  `--optimize` level); `subseq`/`replace`/`search`/`position` needed no special-casing.
- **WASM, a multi-byte string indexed randomly far from its cursor**: O(min(i, |i - ci|)).
  The fix if it matters is a stride index on the same struct, NOT walking from zero.
- **JVM, a surrogate-bearing string** costs the walk from its nearest breakpoint, at most 31
  characters -- a constant, not the index. A WASM-style cursor cannot hang off a
  `java.lang.String`, which is why the table lives beside it instead of in it.
- **JVM, more than two HOT wide strings interleaved** still thrash: the memory holds two, and it
  now keeps the two LONGEST rather than the two most recent (the assumption above). Widen the slot
  count, with a per-slot hit count, before anything more clever.
- **JVM, retention**: the two pairs hold up to four strings alive that the program might otherwise
  have dropped. They are objects the program allocated itself, and the count is fixed; a
  `WeakReference` slot would remove even that, at one `get()` per index on the hottest path.

## Pinning tests
- ci-spec `character-vector-index-reads-the-element`,
  `character-index-is-not-linear-in-the-index` (all four backends): forward/backward/jumping
  orders over one mixed ASCII / 2-byte / 3-byte / astral string, then cost as `SCAN-FLAT` /
  `LENGTH-FLAT` -- 131,072 characters as ONE string against 1,024-character chunks.
  Comparing the two halves is what makes the bound machine-independent.
- ci-spec `character-index-into-a-string-holding-a-surrogate-pair` (all four backends):
  CORRECTNESS only over a string with an astral character in every 16 -- the lengths, three
  indexes, and that a walk of the long string reads exactly what 128 walks of the short one do.
  Built through `rontolisp:octets-to-string` so the JVM half is an immutable string rather than a
  character vector. **No timing token**: a verdict derived from `get-internal-real-time` is a
  machine-speed value inside a file whose contract is exact strings, and a flake in it fails the
  run carrying every other case's evidence. Sizing the scan so a quadratic implementation could
  not finish was priced and rejected -- the driver's ceiling is a fixed
  `CiSpecE2eTest.EXEC_TIMEOUT_SECONDS = 300` per invocation, so at 2^20 characters (the smallest
  size whose n^2 exceeds it, and then only 3.7x) detection is a property of the RUNNER's speed
  rather than of the code, and a timeout names the command, i.e. a backend and not a case.
  The cost belongs in the per-backend pins below.
- `JvmLispCompilerTest.compileACharacterIndexDoesNotWalk{FromTheStartOfTheString,ForANonLatin1String}`
  (the second over Hiragana, so the LATIN1 shortcut cannot carry it),
  `#compileACharacterIndexIntoACharacterVectorReadsTheElement`,
  `#compileACharacterIndexIntoAStringWithASurrogatePairDoesNotWalkFromTheStart`,
  `#compileACharacterIndexKeepsTheLongStringWhenShortOnesAreIndexedBetween` (the tokenizer.json
  mechanism in miniature: two short strings indexed between every two indexes into a long one).
- `LispEvaluatorTest.evalSubseqDoesNotRenderTheWholeStringItCutsFrom`,
  `#evalSubseqOfAStringIsByCodePointAndFollowsAView`.
- `WasmLispCompilerIntegrationTest.aCharacterIndexDoesNotDecodeFromTheStartOf{TheString,AMultiByteString,AStringHoldingAnAstralCharacter}`,
  `#aStringLengthDoesNotRecountTheWholeStringOnEveryCall`,
  `#aCharacterIndexIntoACharacterVectorDoesNotRenderTheVector`. **The COMPONENT leg has no cost
  pin of its own, and does not need one -- but that is an inference, not a measurement**: the
  cursor lives in `TYPE_STRING` in the CORE module, which the component wraps unchanged, and only
  the I/O adapter and the entropy/clock source differ between the two legs. If the string runtime
  ever moves out of the shared core module, or the component grows a string representation of its
  own, this sentence is the notice that a pin went dark with it.

Related: `.kb/characters-code-points.md`, `.kb/wasm-gc-strings.md`,
`.kb/length-runtime.md`, `.kb/string-write-runtime.md`.
