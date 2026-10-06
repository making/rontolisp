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
- `--no-gc`: `__strlen_cp` (counts UTF-8 lead bytes), `__char_at`, `__byte_offset`
  (`codegen.wasm.NoGcWasmCompiler`, `.kb/no-gc-scalar-wasm.md`). Code-point-correct since
  2026-09-14 (`.todo/813`); before that it indexed and measured the byte array. The
  helpers are O(n) scans with no cursor -- the one exception to [[string-index-cost]].

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

**Every bound is checked once, before the walk** -- the rule the sequence operators follow
(`.kb/sequence-bounding-keywords.md`, "Every bound is checked once"), with the same refusal: a
negative, non-integer (a nil start included) or past-int-range bound, one past the string's length
and a start past its end are `subseq`'s `type-error` (datum the refused bound, expected type its
range, report `SUBSEQ: invalid bounds S, E for string of length N`) on every backend, call position
and first class, string1's range before string2's. A nil `:end1`/`:end2` is the length.
- `string<` family: `%string-compare` calls `(%check-bounds sa start1 end1)` and
  `(%check-bounds sb start2 end2)` after the designator coercion and before the walk -- one
  definition, all four backends. The start is bound as given (no `(or start1 0)`).
- `string=`/`string-equal`: the interpreter's `boundedStringArg` refuses through `stringWindow`
  (shared with `boundedCaseConversion`) -> `subseqBoundsError`; the call-position lowering and
  the `stringEquality` wrappers cut with `subseq`, which refuses alike, reading a given start as
  written (`getfKwDefault`, `.kb/sequence-bounding-keywords.md`). Both coerce a designator that is
  no literal string with `(string x)` before the cut: `(string= 'abc "BC" :start1 1)` was
  `SUBSEQ: The value ABC is not of type SEQUENCE` on the JVM and wasm until 2026-10-06.
- Evaluation order: the lowering hoists the operands and every computed bound in the call's order
  when it would otherwise run them in its own, and takes the first of a repeated keyword
  (`.kb/sequence-designator-evaluation.md`, "`string=` / `string-equal` with a bounding keyword").
- Deviations from SBCL, `subseq`'s own: a range is refused with the bound as datum (SBCL's is
  the cons `(start . end)`), and SBCL checks every bound's TYPE before any range (its lambda
  list declares `(MOD ...)`), so `(string= s s :start1 9 :start2 -1)` is its -1 and our 9. SBCL
  2.2.9's `string<`/`string>`/`string<=` over two LITERAL strings check no range at all
  (`(string< "abc" "abd" :end1 9)` -> 2, through `funcall` too; over `(copy-seq "abc")` a
  `type-error`), which is why the fixtures compare strings that are no literals.
- Before (measured 2026-10-06, four backends): only a nil start was refused. The `string<`
  family answered from the walk (`:start1 -1` -> -1 JVM, 0 wasm, the interpreter's `char`
  `type-error`; `:start1 2 :end1 1` -> 2; `:start2 4` -> NIL; `:end1 9` over a `copy-seq`
  string trapped on wasm, out-of-bounds array access); the interpreter's `string=` refused
  with a `simple-error`.
- Cost (2026-10-06, JVM / P1 / component bytes, 792 programs: every ci-spec case, the examples
  in `examples.yaml`, size-report, bench-report): 2,098 of 2,324 artifacts byte-identical, among
  them every program that names no string comparison and every keyword-free `string=`. The
  shared defun cannot tell a keyword-free `string<` from a bounded one, so every program
  carrying the `string<` family pays (the check, plus `_ckBounds` / `_ck_bounds` where nothing
  else brought it): `(print (string< "abc" "abd"))` 22,974 -> 24,057 / 7,527 -> 7,612 / 8,714 ->
  8,799, with a `handler-case` +1,215 / +426 / +433; the ci-spec `string-comparison-family` case
  +652 JVM / +264 P1 on the check alone. The `stringEquality` wrapper's two `(string x)`
  coercions add +343..+686 JVM / +178..+404 wasm to a program carrying it (the compiled `eval`'s
  wrapper table). Differing: JVM 111 (sum +70.1 KB, max +2.2 KB), P1 56 (+27.1 KB, max
  +1.3 KB, the ningle examples), component 59 (+28.4 KB). Selecting an unchecked `%string-compare`
  for a program that provably passes no bound was rejected: a function value, an `apply`, a
  run-time `eval` or a wrapper body injected after prelude selection can all pass one, and a
  wrong "provably" is the silent wrong answer this check exists to remove.
- Speed (2026-10-06, pinned, min of 15 steady-state reps of 400 K calls (P1) / 4 M (JVM), 8
  characters): keyword-free `string<` +4-7% (P1 261-274 -> 275-293 ms; JVM 385-419 -> 406-435),
  bounded `string-lessp` P1 209 -> 229 ms; JVM bounded runs are bimodal (JIT) on base and after
  alike. A guard skipping the check for default bounds won the P1 keyword-free time back but
  measured slower on the JVM's bounded loop, and costs bytes on every program. Bounded `string=`
  over variables (now coerced with `string`): within noise.
- ANSI `strings` (interpreter, suite `ca06bd9`, 2026-10-06): 435 / 509 before and after, the
  FAIL/ERROR sets identical name by name.
- Pinned by `StringComparisonBoundsFixture` (`PROGRAM`: sbcl's answers; `REPORT_PROGRAM`: the
  slots and text) in `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`
  (P1 and component), ci-spec `string-comparisons-refuse-a-bad-bound`; the nil start also by
  `StringNilStartFixture` and ci-spec `string-operators-refuse-a-nil-start`.

The `nstring-*` case conversions take `&key start end` and delegate the window to the
non-destructive sibling (`.kb/subseq-runtime.md`, "Bounded string operators"), taking the
unbounded fold when the window is the whole string. The interpreter's keyword walk honors the
FIRST occurrence of a repeated keyword (CLHS 3.4.1.4).
- Cost of refusing a nil start (measured 2026-10-05, JVM / P1 / component bytes, over every
  ci-spec case, the examples in `examples.yaml`, size-report and bench-report: 2,408 compiled
  artifacts): 2,180 are byte-identical; the rest shrink -- a `funcall`ed `#'string=` with bounds
  14,341 -> 13,944 JVM, 14,050 -> 13,749 P1, 15,266 -> 14,963 component (a program carrying the
  compiled `eval`'s wrapper table -798 to -935 JVM, -700 to -798 wasm); a `string<` program
  -137 B JVM, 0 to -22 B wasm -- summed -60.5 KB JVM, -27.5 KB P1, -27.6 KB component. An
  `nstring-*` program grows: `(print (nstring-upcase (copy-seq "abc")))` 27,390 -> 30,977 JVM
  (the `&key` parsing the program had no other use for), 22,286 -> 23,079 P1, 23,500 -> 24,289
  component; with another `&key` defun in the program +1,030 JVM / +837 P1; the ci-spec case
  using all three +4,908 JVM / +798 P1 / +820 component. Speed: a keyword-free
  `nstring-upcase` loop is within noise on P1 and the JVM; routed through the bounded fold it
  measured ~1.25x (P1) / ~2x (JVM) slower, which is why the whole-string window skips it.

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
  compare (`WasmStringRuntimeBuilder.emitMaybeLower`); tracked separately (`.todo/269`). The Scheme
  `(scheme char)` classifiers go around them, through tables generated from the JDK
  (`.kb/scheme-frontend.md`).

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
- **The first character after `#\` is read as a CODE POINT, not a UTF-16 `char`**
  (`LispLexer.readChar`; `FormatReader.readCharLiteral` for the formatter's CST front end
  mirrors it). A supplementary-plane literal (`#\😀`, U+1F600) is a surrogate PAIR in the
  UTF-16 source; scanning one unit at a time used to read only the high surrogate and leave
  the low surrogate to be lexed as its own, unrelated token (an unbound-variable error at
  eval time, or a truncated/corrupted literal out of the formatter). `SchemeReader.readCharacter`
  already scanned by code point and was never affected. Fixed 2026-09-26 (`.todo/a21`); pinned by
  `LispReaderTest#readsASupplementaryPlaneCharacterLiteralAsOneCharacter`,
  `LispEvaluatorTest#evalSupplementaryPlaneCharacterLiteralReadsAsOneCharacter`,
  `LispFormatterTest#keepsASupplementaryPlaneCharacterLiteralWhole` and the `#\` literal lines
  added to ci-spec `code-point-characters-beyond-ascii` (all four backends, including native).

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
(`rontolisp::%octets-to-string` / `%octets-to-string-packed`, `.kb/fetch-http.md`,
`.kb/http-server.md`): a byte that leads no valid sequence, and a sequence the vector's end cuts
short, both decode to their OWN byte value as a one-character result, never a signal. An overlong
encoding and a UTF-8-encoded surrogate are NOT rejected by the lenient rule (only the strict
validator refuses them, and refusing falls through to the lenient arms) -- each decodes to the code
point its bits assemble, since a CHARACTER admits any code point 0..`#x10FFFF` including surrogates
(above). Two of them side by side stay two characters on the interpreter and wasm, and read back as
the one supplementary character they pair into on the JVM, whose strings are UTF-16
(`.kb/async-await.md`, "`read-all` is prelude Lisp"). Consequence: `octets-to-string` then
`string-to-octets` round-trips only for a WELL-FORMED,
non-overlong, non-truncated input -- a malformed byte's lenient answer does not generally re-encode
to the same bytes. **Encode is total** over every code point with no malformed case at all.

**The interpreter's native `%octets-to-string` used to reject any argument that was not
literally a `LispIntVector`, while the compiled backends' fallback fell through the
prelude's own `length`/`aref` loop and so tolerated a general (boxed) array too** --
exactly what a general array's `subseq` answered before the packed-width fix
(`Environment.packedCopyForElementType`, `.todo/698`), which is how the asymmetry
surfaced: the same program crashed on the interpreter and quietly decoded on the JVM and
WASM. `Environment.asOctetVector` closed it by widening the native mirror to accept any
rank-1 array of integers, matching the Lisp source's own acceptance -- only a genuinely
non-array argument still signals. `%octets-to-string-packed` is unaffected: answering
`nil` for anything that is not a packed byte vector (rather than a general array too) is
its designed declines-to-the-loop contract, not the asymmetry.

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
