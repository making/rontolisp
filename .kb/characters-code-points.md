# CHARACTER = Unicode code point (all four backends)

A rontolisp CHARACTER is a Unicode code point in `[0, 0x10FFFF]`, NOT a UTF-16 code
unit; the invariant holds byte-identically on the interpreter, the JVM compile path,
WASM P1 and the WASM component.

## Value model per backend

| backend | representation | discriminator |
| --- | --- | --- |
| interpreter | `record LispChar(int codePoint)` in `am.ik.rontolisp` | `instanceof LispChar` |
| JVM compile | length-1 `int[]{codePoint}` | `instanceof int[]` |
| WASM (P1 + component) | `TYPE_CHAR = struct { i32 code }` | `ref.test $type_char` |

The interpreter's mutable-string storage is a matching `int[]` (one code point per slot
in `LispString.chars`), so `(setf (schar s i) (code-char cp))` writes one indexed slot
on every backend for any code point in range.

JVM notes: chars were `java.lang.Character` (16-bit), so `#\U+1F600` truncated to a
lone surrogate; widening to `int[]{cp}` fixed it (sibling widening of the WASM string
byte model: [[wasm-gc-strings]]). The discriminator is disjoint from `Object[]`
(cons / function value), `BigInteger[]` (ratio), `double[]`/`float[]` (packed float
arrays), so `_eqv`/`_eq`/`characterp`/instanceof dispatch stay unambiguous. Java does
not cache `int[]` literals the way `Character.valueOf(char)` caches 0..127, so `_eqv`
has a first-branch check for both-`int[]` operands comparing `arr[0]`, keeping
`(eq (code-char 65) #\A)` = `T`.

## String indexing is by code point

`length` / `char` / `schar` / `aref` (rank-1 string) / `subseq` / `elt` / `position` /
`search` / `find` / `mismatch` / `string-capitalize` all walk BY CODE POINT. A
supplementary code point counts as one indexed character: `(length "😀") == 1`,
`(char "aé😀b" 2) == #\😀` on every backend.

- **Interpreter** (`Environment.java`): `LENGTH` reads `str.codePointCount()`;
  `charRef` reads `LispString.codePointAt(i)` (ONE array slot); `sequenceRef` walks
  `String.offsetByCodePoints(0, i)` + `String.codePointAt(codeUnit)` on the reassembled
  `value()`; `SUBSEQ` translates a character range to a code-unit range through the
  same walk; `seqAsList` builds one `LispChar` per code point via
  `String.codePointBefore(codeUnit)`. `capitalizeString`/`trimString` walk by code
  point via `Character.isLetterOrDigit(int)` and `Character.toUpperCase(int)` /
  `toLowerCase(int)`. Mutation (`%schar-set`, `storeStringChar`, `vectorPushExtend`)
  writes one code point into one slot — no BMP-only reject.
- **JVM compile path**: `JvmLengthRuntimeBuilder` returns
  `str.codePointCount(1, length()-1)` on the string branch (framing quotes at 0 and
  `length-1`). `JvmCharCompiler.compileChar` and `JvmArrayRuntimeBuilder._aref1`'s
  string branch call `String.offsetByCodePoints(1, index)` ->
  `String.codePointAt(codeUnit)` and box as `int[]{cp}`. `JvmSubseqCompiler`'s string
  arm translates bounds the same way. `JvmStringCapitalizeCompiler` walks code points
  and appends via `StringBuilder.appendCodePoint(int)`. `_strv` (the mutable char-vec
  normalizer) reads each element as `int[]` and appends via `appendCodePoint`.
- **WASM (both)**: see [[wasm-gc-strings]]. `_charvec_to_str` emits each code point as
  its 1-4 byte UTF-8 sequence; `_str_char_count` counts UTF-8 lead bytes;
  `_str_char_at` decodes the sequence at a walked position; `_str_char_byte_offset`
  translates a character index to a byte offset for `subseq`.

**Cost is uniform**: a character index is O(1) or amortized O(1) on all four backends,
so a left-to-right `dotimes` scan is LINEAR everywhere — the WASM string carries a
character-index cursor and the JVM proves its string free of surrogate pairs and
remembers that. Mechanism and what is still not constant: [[string-index-cost]].
Legacy consequence: generated bulk data was cut into MANY SHORT string literals to keep
each (then quadratic) scan bounded — `eval/Uax15Tables` still cuts derived runs at
1,000 characters ([[asdf]]), now worth nothing.

## String comparison family = ONE code-point walk

`string<` / `string>` / `string<=` / `string>=` / `string/=` and the case-insensitive
`string-lessp` / `string-greaterp` / `string-not-greaterp` / `string-not-lessp` /
`string-not-equal` are ten one-liners over one shared `LispPreludeLibrary` defun
`%string-compare`, returning `(order . mismatch-index)`: `order` is `-1`/`0`/`1` for
substring1 before/equal/after substring2, and `mismatch-index` is the index **into
string1** of the first differing character — `end1` when equal, which is what
`string<=`/`string>=`/`string-not-greaterp`/`string-not-lessp` must return for equal
strings. Being one rontolisp-source defun, the walk IS the same code on all four
backends, and stepping with `char`/`char<`/`char-downcase` gives code-point basis and
full-Unicode case folding for free (`(string< "あい" "あう")` = 1 everywhere). It is
iterative (a `while` over a `result` accumulator), so long strings cannot exhaust the
stack.

`string=` / `string-equal` are the exception: per-backend intrinsics (`Environment` +
`Jvm/WasmStringEqCompiler`) because two-argument string equality is hot. Their
`:start1`/`:end1`/`:start2`/`:end2` shape is therefore handled THREE times,
deliberately:

1. interpreter parses the keywords in Java (`Environment.boundedStringArg`, code-point
   bounds like `subseq`'s);
2. both compilers lower the call onto `subseq` first
   (`LispMacroExpander.expandStringComparisonBounds`, dispatched from
   `Jvm/WasmExprCompiler` only when `hasStringComparisonBounds`), so the intrinsic
   always sees two plain strings and a keyword-free call compiles byte-identically;
3. the `#'string=` / `#'string-equal` first-class values: their
   `BuiltinFunctionWrappers` entries (`stringEquality`) are `(a b &rest kw)` and
   re-extract the bounds with `getf`, falling back to the direct two-argument call when
   `kw` is nil — without that, `(apply #'string= a b :start1 1)` would silently ignore
   the keywords on the compile paths while the interpreter honoured them.

The ordering predicates need none of this (ordinary defuns whose lambda list takes the
four keywords).

Compile-path consequence: a program calling `string>` never mentions `%string-compare`,
so `LispPreludeLibrary.process` selects the prelude entries to splice **to a fixpoint**
(a pulled-in definition drags in what it references). The interpreter resolves prelude
names lazily.

Pinned by ci-spec `string-comparison-family` (all four backends),
`LispEvaluatorTest#evalStringOrderingPredicates` (+ case-insensitive / bounding-index /
designator siblings), `JvmLispCompilerTest#compileAndRunStringOrderingPredicates`,
`WasmLispCompilerIntegrationTest#stringOrderingPredicates`.

## Case fold is FULL Unicode, and PER CODE POINT everywhere

`char-upcase` / `char-downcase` produce the same code point
`Character.toUpperCase(int)` / `toLowerCase(int)` would on the same JVM Unicode
baseline. **`string-upcase` / `string-downcase` / `string-capitalize` apply that same
single-code-point fold to EVERY character** (CLHS: "each character of the result string
is produced by applying `char-upcase` to the corresponding character"). Two load-bearing
consequences:

- Same CHARACTER COUNT as the argument; no multi-character special casing:
  `(string-upcase "straße")` is `"STRAßE"`, not `"STRASSE"` (SBCL agrees).
- No context-sensitive rule: `(string-downcase "ΑΣ")` is `"ασ"`, not `"ας"`.

Interpreter and JVM used to call `String.toUpperCase(Locale.ROOT)` /
`toLowerCase(Locale.ROOT)`, which DOES apply the 102 upper / 1 lower SpecialCasing
expansions and Final_Sigma, while WASM folded ASCII only. Both sides were corrected
together; do NOT "simplify" either back to the `String` overload.

- **Interpreter**: `Environment.caseFoldString` walks code points applying
  `Character.toUpperCase(int)` / `toLowerCase(int)`; `Environment.capitalizeString`
  does the same with the `Character.isLetterOrDigit(int)` word test.
- **JVM**: one shared emitter `JvmStringCaseFold` walks the quoted designator by
  `String.codePointAt` / `Character.charCount` and appends via
  `StringBuilder.appendCodePoint(int)`. `JvmStringUpcaseCompiler` and
  `JvmStringCapitalizeCompiler` are thin mode selectors over it. The framing quote
  bytes are neither cased nor alphanumeric, so they survive the walk.
- **WASM (both)**: `_char_upcase` / `_char_downcase` (`WasmCaseFoldRuntimeBuilder`)
  binary-search a compressed `(from:u32, to:u32, delta:i32)` range table baked into
  static data, generated from `Character.toUpperCase/toLowerCase` at compile time.
  ~16 KB combined (upper 690 ranges, lower 674 at Unicode 15), 10-deep search per call.
  `_string_upcase` / `_string_downcase` / `_string_capitalize`
  (`WasmStringRuntimeBuilder.emitCaseFoldCore`) DECODE each 1-4 byte UTF-8 sequence,
  call those helpers on the code point, and re-encode — they must never fold bytes.
  `_string_capitalize` finds word boundaries with `_char_alnum_p`
  (`FUNC_CHAR_ALNUM_P`), the same binary search over a `(from, to)` PAIR table from
  `Character.isLetterOrDigit(int)` (728 ranges / ~5.8 KB at Unicode 16); an ASCII alnum
  test would restart a word after a caseless letter and answer `"AあB"` where the others
  answer `"Aあb"`. All three tables are their own `WasmTreeShaker.OwnedDataSegment`
  owned by their sole reader, so `--optimize` drops each with its helper.

**Output-buffer sizing on WASM is derived, not assumed.** A fold can WIDEN a UTF-8
encoding (`U+0250` upcases to `U+2C6F`: two bytes in, three out), so `emitCaseFoldCore`
grows the scratch to
`inputBytes * (1 + WasmCaseFoldRuntimeBuilder.maxUtf8Growth()) + 2`. `maxUtf8Growth()`
is computed from the baked tables at compile time (1 today), valid because a code point
occupies at least one input byte — a future JDK Unicode baseline that folds more widely
widens the bound instead of overrunning.

`--no-gc` rejects `string-upcase`/`string-downcase`/`string-capitalize` at
`collectCalls`, so it is not a fifth opinion.

**Still ASCII-only on WASM and therefore divergent**: `alpha-char-p`
(`WasmCharCompiler.compileAlphaCharP`) and the `string-equal` / `char-equal`
case-insensitive compare (`WasmStringRuntimeBuilder.emitMaybeLower`), where the
interpreter uses `Character.isLetter(int)` and `equalsIgnoreCase`. Those need their own
membership table and a fold-based compare; tracked separately.

## Character NAMES in `#\`

- **Short names** (`LispLexer.charByName`, mirrored by the two RUNTIME readers
  `JvmReadRuntimeBuilder` / `WasmReadRuntimeBuilder`, whose tables must stay in step):
  `Space`, `Newline`/`Linefeed`/`Lf`, `Tab`, `Return`/`Cr`, `Page`, `Backspace`,
  `Vt`/`Vertical-Tab` (U+000B), `Bell`/`Bel` (U+0007), `Nul`/`Null`,
  `Rubout`/`Delete`/`Del`, `Escape`/`Altmode`/`Esc`. `Vt` is what a portable whitespace
  list is written with (cl-str's `*whitespaces*`).
- **Unicode names** (`LispLexer.unicodeCharByName` ONLY): a character with no short
  name is spelled by its UCD name with spaces as underscores — `#\No-break_space`,
  `#\Ideographic_space`, `#\GREEK_SMALL_LETTER_ALPHA` — matched case-insensitively
  through `Character.codePointOf`. Source is read by the frontend lexer on EVERY
  backend and none of the JDK's table travels into a compiled program, so a name that
  reads here reads on all four; pinned by ci-spec `character-names-short-and-unicode`.

The long spelling is deliberately NOT in the two runtime readers: a
`(read-from-string "#\\IDEOGRAPHIC_SPACE")` at RUN TIME would need the name table
inside the artifact (the cl-unicode size problem). The short names round-trip.

## Print / read

- `princ` on a CHARACTER prints its glyph via `Character.toString(int)`
  (interpreter, JVM) or its 1-4 byte UTF-8 sequence (WASM:
  `emitPrintChar.emitGlyph` -> `_write_str`).
- `prin1` prints `#\Space` / `#\Newline` / ... for the standard non-graphic set
  (`LispChar.name`, `_charPrin1` on the JVM, `emitPrintChar` on WASM) and `#\<glyph>`
  otherwise.
- A `#\` reader token in source is read as a code point directly (the reader decodes
  source UTF-8 verbatim).
- `read-char` returns a CHARACTER holding a full code point on every backend.
  Interpreter and JVM: a `BufferedReader.read()` high-surrogate code unit is combined
  with the peeked low half via `mark(1)` + conditional `reset()` before boxing. WASM:
  `_read_char` decodes a 1-4 byte UTF-8 sequence at the cursor by dispatching on the
  lead byte's high bits (the `<0x80 / <0xE0 / <0xF0 / else` ladder `_str_char_at`
  uses); the string-stream branch clamps against the buffer end, the WASI fd branch
  against successive `fd_read` yields (a truncated tail falls back to the lead byte as
  a bare CHARACTER on both paths, matching the interpreter). A supplementary code point
  round-trips as one CHARACTER through `(with-input-from-string ... (read-char ...))`
  byte-identically on all four.

## Java interop (JVM compile path)

`JavaBridgeTemplate` marshals CHARACTER at the reflection boundary:

- **In (Lisp -> Java)**: a length-1 `int[]{cp}` coerces to `char`/`Character` when the
  parameter is char-typed AND `Character.isBmpCodePoint(cp)`. It also coerces to
  `int`/`Integer`; a supplementary code point bridges through the `int` path only.
- **Out (Java -> Lisp)**: a `java.lang.Character` return becomes
  `new int[] { c.charValue() }`.

## Mutation semantics

`(setf (aref s i) ch)` / `%schar-set` on a mutable string
(`make-array N :element-type 'character`) stores one CHARACTER per slot on every
backend: interpreter `LispString` holds `int[]`, the JVM char-vec holds `int[]{cp}` per
ArrayList slot (normalized to a String at read-out via `appendCodePoint`), WASM holds
`TYPE_CHAR` per slot. Capacity, fill pointer and index are all in code-point units. On
the two compile paths the write goes through the shared `%schar-set-runtime` defun
rather than being expanded at the site (`.kb/string-write-runtime.md`).

## eq on CHARACTER = char= (implementation-defined but pinned)

CL permits `eq` on two `char=` characters to return `T`, and every backend takes that
permission; each rep is a value object, so two allocations with the same code point
compare equal.

- **Interpreter**: `LispChar` is a record; its derived value-based `equals` is what the
  `eq` primitive walks through for a `LispChar` operand.
- **JVM**: `_eqv` (which `eq` reaches via `_eq`) early-branches on two length-1 `int[]`
  operands and compares `arr[0]`.
- **WASM (both)**: `emitEqComparison` in `WasmEmitHelper` follows a `ref.eq`-false miss
  with a `ref.test $type_char` guard on both operands and compares the code-point
  field. `emitEqlComparison` uses the same shared helper
  (`emitCharCodePointEqOrElse`). `_equal` (`WasmRuntimeBuilder.buildEqualBody`) already
  had its own TYPE_CHAR branch.

Result: `(eq (code-char cp) (code-char cp))` = `T` everywhere;
`(eq (code-char 128512) #\A)` = `NIL`.

## Tests

- ci-spec `code-point-characters-beyond-ascii` — `code-char`/`char-code` round-trip on
  Latin-1 supplement, Greek, Cyrillic, astral (`U+1F600`); case fold on those scripts;
  `length`/`char`/`subseq` on a mixed BMP+astral string; read-side predicates on ASCII;
  the mutation round-trip (`(setf (schar s i) (code-char 128512))` +
  `vector-push-extend`) landing a supplementary code point in one slot; and the
  `(with-input-from-string ... (read-char ...))` round-trip.
- ci-spec `eq-on-characters-by-code-point` — `(eq #\A #\A)`,
  `(eq (code-char 65) #\A)`, `(eq (code-char 128512) (code-char 128512))` all `T`
  (including a `let`-bound cp defeating constant folding), `(eq (code-char 128512) #\A)`
  `NIL`, and that `eq` on freshly-consed cells and two boxed floats stays `NIL`.
- ci-spec `string-case-ops-full-unicode` — `string-upcase`/`string-downcase`/
  `string-capitalize` on Latin-1, Greek, Cyrillic and astral (Deseret) input, the
  length-preserving sharp s and non-context final sigma, the two UTF-8-widening folds
  (`U+0250`, `U+023A`), and full-Unicode word constituents.
- ci-spec `character-names-short-and-unicode`.
- `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest` cover
  per-backend char builtins and the WASM `_char_upcase`/`_char_downcase` helper
  indices; `WasmLispCompilerIntegrationTest#eqOnCharactersComparesByCodePoint`,
  `#stringCaseOpsAreFullUnicode`, `#stringCapitalizeWordConstituentsAreFullUnicode`;
  `LispEvaluatorTest#evalStringCaseOpsFoldEveryCharacterIndependently`,
  `#evalStringCapitalizeIsFullUnicode`,
  `JvmLispCompilerTest#compileAndRunStringCaseOpsAreFullUnicodeAndLengthPreserving`.

## Related

- [[string-index-cost]] — what makes those walks amortized O(1) (the WASM per-string
  cursor, the JVM surrogate-pair proof).
- [[wasm-gc-strings]] — the WASM byte model, and the mem-module-min-pages patch that
  unblocked uax-15's static UnicodeData.
- [[reader-case-upcase]] — the reader's uppercase-canonical fold (SYMBOL names, not
  CHARACTER values).
