# CHARACTER = Unicode code point (all four backends)

A rontolisp CHARACTER is a Unicode code point in `[0, 0x10FFFF]`, NOT a UTF-16 code unit; the
invariant holds byte-identically on the interpreter, the JVM compile path, WASM P1 and the WASM
component.

## Value model
| backend | representation | discriminator |
| --- | --- | --- |
| interpreter | `record LispChar(int codePoint)` in `am.ik.rontolisp` | `instanceof LispChar` |
| JVM compile | length-1 `int[]{codePoint}` | `instanceof int[]` |
| WASM (both) | `TYPE_CHAR = struct { i32 code }` | `ref.test $type_char` |

The interpreter's mutable-string storage is a matching `int[]` (`LispString.chars`), so
`(setf (schar s i) (code-char cp))` writes one indexed slot on every backend. The JVM
discriminator is disjoint from `Object[]` (cons/function), `BigInteger[]` (ratio) and
`double[]`/`float[]` (packed float arrays). Java does not cache `int[]` literals, so `_eqv` has a
first-branch check for both-`int[]` operands comparing `arr[0]`.

## String indexing is by code point
`length` / `char` / `schar` / `aref` / `subseq` / `elt` / `position` / `search` / `find` /
`mismatch` / `string-capitalize` all walk BY CODE POINT: `(length "😀") == 1`.
- Interpreter (`Environment`): `codePointCount()`, `LispString.codePointAt`,
  `String.offsetByCodePoints`, `capitalizeString`/`trimString` via `Character.toUpperCase(int)`.
- JVM: `JvmLengthRuntimeBuilder` (`codePointCount(1, length()-1)` -- framing quotes at 0 and
  `length-1`), `JvmCharCompiler.compileChar`, `JvmArrayRuntimeBuilder._aref1`, `JvmSubseqCompiler`,
  `JvmStringCapitalizeCompiler`, `_strv`.
- WASM: `_charvec_to_str`, `_str_char_count` (counts UTF-8 lead bytes), `_str_char_at`,
  `_str_char_byte_offset`. See [[wasm-gc-strings]].

**Cost is uniform**: a character index is O(1) or amortized O(1) on all four, so a left-to-right
`dotimes` scan is LINEAR everywhere ([[string-index-cost]]). Legacy consequence: generated bulk
data was cut into MANY SHORT string literals to keep each then-quadratic scan bounded --
`eval/Uax15Tables` still cuts derived runs at 1,000 characters, now worth nothing.

## String comparison family = ONE code-point walk
`string<`/`>`/`<=`/`>=`/`/=` and `string-lessp`/`-greaterp`/`-not-greaterp`/`-not-lessp`/
`-not-equal` are ten one-liners over one shared `LispPreludeLibrary` defun `%string-compare`,
returning `(order . mismatch-index)`; `mismatch-index` is the index **into string1** of the first
difference, `end1` when equal (what `string<=`/`>=` must return for equal strings). Being one
rontolisp-source defun, the walk IS the same code on all four backends and gets code-point basis
and full-Unicode case folding for free. Iterative, so long strings cannot exhaust the stack.

`string=`/`string-equal` are the exception: per-backend intrinsics (`Environment` +
`Jvm`/`WasmStringEqCompiler`) because two-argument string equality is hot. Their
`:start1`/`:end1`/`:start2`/`:end2` shape is therefore handled THREE times, deliberately:
1. interpreter parses the keywords in Java (`Environment.boundedStringArg`);
2. both compilers lower the call onto `subseq` first
   (`LispMacroExpander.expandStringComparisonBounds`, dispatched only when
   `hasStringComparisonBounds`), so a keyword-free call compiles byte-identically;
3. the `#'string=`/`#'string-equal` wrappers (`BuiltinFunctionWrappers.stringEquality`) are
   `(a b &rest kw)` and re-extract bounds with `getf` -- without that,
   `(apply #'string= a b :start1 1)` would silently ignore them on the compile paths.

Compile-path consequence: a program calling `string>` never mentions `%string-compare`, so
`LispPreludeLibrary.process` selects prelude entries **to a fixpoint**. The interpreter resolves
prelude names lazily.

## Case fold is FULL Unicode, and PER CODE POINT everywhere
`string-upcase`/`string-downcase`/`string-capitalize` apply the single-code-point
`char-upcase`/`char-downcase` fold to EVERY character. Two load-bearing consequences: same
CHARACTER COUNT as the argument (`(string-upcase "straße")` is `"STRAßE"`, SBCL agrees), and no
context-sensitive rule (`(string-downcase "ΑΣ")` is `"ασ"`). Interpreter and JVM used to call
`String.toUpperCase(Locale.ROOT)`, which DOES apply SpecialCasing and Final_Sigma; **do NOT
"simplify" either back to the `String` overload.**
- Interpreter: `Environment.caseFoldString` / `capitalizeString` walk code points.
- JVM: one shared emitter `JvmStringCaseFold`; `JvmStringUpcaseCompiler` and
  `JvmStringCapitalizeCompiler` are thin mode selectors over it.
- WASM: `_char_upcase`/`_char_downcase` (`WasmCaseFoldRuntimeBuilder`) binary-search a compressed
  `(from:u32, to:u32, delta:i32)` range table in static data (~16 KB, 690 upper / 674 lower
  ranges at Unicode 15). `_string_upcase`/`_downcase`/`_capitalize`
  (`WasmStringRuntimeBuilder.emitCaseFoldCore`) DECODE each UTF-8 sequence, fold the code point,
  re-encode -- they must never fold bytes. `_string_capitalize` finds word boundaries with
  `_char_alnum_p` over a `(from, to)` PAIR table (728 ranges / ~5.8 KB at Unicode 16); an ASCII
  test would answer `"AあB"` where the others answer `"Aあb"`. All three tables are their own
  `WasmTreeShaker.OwnedDataSegment`, so `--optimize` drops each with its helper.
- **Output-buffer sizing on WASM is derived, not assumed**: a fold can WIDEN a UTF-8 encoding
  (`U+0250` -> `U+2C6F`, two bytes in, three out), so `emitCaseFoldCore` grows the scratch to
  `inputBytes * (1 + WasmCaseFoldRuntimeBuilder.maxUtf8Growth()) + 2`, computed from the baked
  tables at compile time (1 today).
- `--no-gc` rejects the three at `collectCalls`, so it is not a fifth opinion.
- **Still ASCII-only on WASM and therefore divergent**: `alpha-char-p`
  (`WasmCharCompiler.compileAlphaCharP`) and the `string-equal`/`char-equal` case-insensitive
  compare (`WasmStringRuntimeBuilder.emitMaybeLower`); tracked separately.

## Character NAMES in `#\`
- **Short names** (`LispLexer.charByName`, mirrored by the two RUNTIME readers
  `JvmReadRuntimeBuilder` / `WasmReadRuntimeBuilder`, whose tables must stay in step): `Space`,
  `Newline`/`Linefeed`/`Lf`, `Tab`, `Return`/`Cr`, `Page`, `Backspace`, `Vt`/`Vertical-Tab`,
  `Bell`/`Bel`, `Nul`/`Null`, `Rubout`/`Delete`/`Del`, `Escape`/`Altmode`/`Esc`.
- **Unicode names** (`LispLexer.unicodeCharByName` ONLY): the UCD name with spaces as
  underscores, matched case-insensitively through `Character.codePointOf`. Read by the frontend
  lexer on EVERY backend, and none of the JDK's table travels into a compiled program. The long
  spelling is deliberately NOT in the two runtime readers -- a run-time
  `(read-from-string "#\\IDEOGRAPHIC_SPACE")` would need the name table inside the artifact.

## Print / read
- `princ` prints the glyph (`Character.toString(int)`, or the UTF-8 sequence on WASM via
  `emitPrintChar.emitGlyph` -> `_write_str`); `prin1` prints `#\Space` etc. for the standard
  non-graphic set (`LispChar.name`, `_charPrin1`, `emitPrintChar`) and `#\<glyph>` otherwise.
- `read-char` returns a full code point everywhere. Interpreter/JVM combine a
  `BufferedReader.read()` high surrogate with the peeked low half via `mark(1)` + conditional
  `reset()`. WASM `_read_char` decodes 1-4 bytes by dispatching on the lead byte's high bits (the
  `<0x80 / <0xE0 / <0xF0 / else` ladder); a truncated tail falls back to the lead byte as a bare
  CHARACTER on both the string-stream and WASI fd paths, matching the interpreter.

## Java interop (JVM compile path)
`JavaBridgeTemplate`: a length-1 `int[]{cp}` coerces to `char`/`Character` when the parameter is
char-typed AND `Character.isBmpCodePoint(cp)`, and to `int`/`Integer` (the only path a
supplementary code point takes); a `java.lang.Character` return becomes `new int[]{c.charValue()}`.

## Mutation and eq
- `(setf (aref s i) ch)` / `%schar-set` stores one CHARACTER per slot on every backend; capacity,
  fill pointer and index are in code-point units. On the two compile paths the write goes through
  the shared `%schar-set-runtime` defun, not the site ([[string-write-runtime]]).
- `eq` on two `char=` characters is `T` on every backend (CL permits it; each rep is a value
  object): the interpreter's record `equals`, the JVM's `_eqv` `int[]` branch, and WASM's
  `emitEqComparison`/`emitEqlComparison` following a `ref.eq`-false miss with a
  `ref.test $type_char` guard (`emitCharCodePointEqOrElse`; `_equal` already had its branch).

## The UTF-8 <-> octets codec pair (`.todo/691`)
`rontolisp:octets-to-string` / `rontolisp:string-to-octets` are the only sanctioned way to cross
between a packed `(unsigned-byte 8)` vector and a string; a program hand-writing continuation-byte
arithmetic against either direction is the bug 691 closed. Both are plain `LispPreludeLibrary`
defuns (`am.ik.rontolisp.eval.LispPreludeLibrary`, keys `LispNames.OCTETS_TO_STRING` /
`STRING_TO_OCTETS`) -- no per-backend compiler case, no `Environment.defineFunction`: the decoder
delegates to the existing internal `rontolisp::%octets-to-string` (below), and the encoder is total
arithmetic over primitives every backend already compiles, so ordinary prelude splicing carries
both. **Do not add a `BuiltinFunctionWrappers` entry for either** -- that catalog is for names
`evalCons`/`compileCons` lower BEFORE generic function-call resolution ever runs (bfloat16-bits,
`typep`, ...); adding one for an ordinary prelude defun makes `resolveFunction`'s wrapper fallback
fire before the prelude ever loads the real definition, and the wrapper's own body calls the same
unresolved name again -- infinite recursion on the first `#'octets-to-string` or bare call.

**Decode is total and lenient, arm for arm the pre-existing internal decoder**
(`rontolisp::%octets-to-string` / `%octets-to-string-strict`, `.kb/fetch-http.md`,
`.kb/http-server.md`): a byte that leads no valid sequence, and a sequence the vector's end cuts
short, both decode to their OWN byte value as a one-character result, never a signal. An overlong
encoding and a UTF-8-encoded surrogate are NOT rejected by the lenient fallback (only the strict
platform decoder refuses them, and refusing falls through to lenient) -- each decodes to the code
point its bits assemble, since a CHARACTER admits any code point 0..`#x10FFFF` including surrogates
(above). Consequence: `octets-to-string` then `string-to-octets` round-trips only for a WELL-FORMED,
non-overlong, non-truncated input -- a malformed byte's lenient answer does not generally re-encode
to the same bytes. **Encode is total** over every code point with no malformed case at all.

**Framing is not decoding, and the round-trip pair cannot replace a length classifier.**
`encode(decode(x)) = x` looks like a byte-count-free way to ask "how many of these bytes are safe to
act on now" (a streaming printer holding back an incomplete tail, or a decoder dropping one) -- it
is not: a byte that leads NO valid sequence at all (a real SentencePiece byte-fallback token, e.g.
`<0xC0>`) never round-trips at any prefix length, so the technique either stalls several calls
waiting for bytes that will never validate it, or -- worse, over a FIXED buffer that never grows
past the point a fallback would flush it -- drops the byte silently. Tried and reverted while
landing 691; `examples/llm/llm.lisp`'s `utf8-length`/`complete-prefix`/`print-complete` and
`eval/tokenizers.lisp`'s `tokenizer::%utf8-lead-length`/`%complete-byte-prefix` each keep their OWN
hand-written lead-byte length table for exactly this reason -- FRAMING ("how many bytes"), never
DECODING ("what character"), and 691's own verify step ("no continuation-byte mask in `examples/`")
did not anticipate the distinction and was not fully satisfiable as written. `.todo/699` tracks
folding the two copies (kept in step today only by a comment in each pointing at the other, plus
`TokenizersLibraryTest`'s exhaustive 0..255 pin on the library half) into one shared surface, with
the same constraint that made this non-trivial: an example may reach only a package's PUBLIC
symbols, and the framer must stay separable from the decoder.

## Tests
ci-spec `code-point-characters-beyond-ascii`, `eq-on-characters-by-code-point`,
`string-case-ops-full-unicode`, `character-names-short-and-unicode`, `string-comparison-family`,
`octets-string-conversions`, `tokenizer-decode-drops-an-incomplete-trailing-sequence` (all four
backends). Per-backend: `LispEvaluatorTest#evalStringOrderingPredicates`,
`#evalStringCaseOpsFoldEveryCharacterIndependently`, `#evalStringCapitalizeIsFullUnicode`;
`JvmLispCompilerTest#compileAndRunStringOrderingPredicates`,
`#compileAndRunStringCaseOpsAreFullUnicodeAndLengthPreserving`;
`WasmLispCompilerIntegrationTest#stringOrderingPredicates`, `#eqOnCharactersComparesByCodePoint`,
`#stringCaseOpsAreFullUnicode`, `#stringCapitalizeWordConstituentsAreFullUnicode`;
`TokenizersLibraryTest#decodeBytesIsTheStreamingHalf`,
`#utf8LeadLengthIsHowManyBytesNotWhatCharacterOverEveryLeadByte` (the framer, by value, 0..255).

## Related
[[string-index-cost]], [[wasm-gc-strings]], [[reader-case-upcase]] (SYMBOL names, not CHARACTER
values), [[fetch-http]] / [[http-server]] (the internal decoder this pair's decode half wraps),
[[tokenizers]] / [[gguf]] (the two libraries whose hand-written decoders 691 deleted).
