# CHARACTER = Unicode code point (all four backends)

Detail behind the CLAUDE.md constraint. Scope: every backend -- interpreter, JVM
compile path, WASM P1, WASM component. A rontolisp CHARACTER is a Unicode code
point in the closed range `[0, 0x10FFFF]`, NOT a UTF-16 code unit; the invariant
holds byte-identically across all four.

## Value model per backend

| backend | representation | discriminator |
| --- | --- | --- |
| interpreter | `record LispChar(int codePoint)` in `am.ik.rontolisp` | `instanceof LispChar` |
| JVM compile | length-1 `int[]{codePoint}` (a plain Java `int[]`) | `instanceof int[]` |
| WASM (both P1 and component) | `TYPE_CHAR = struct { i32 code }` | `ref.test $type_char` |

The interpreter's mutable-string storage is a matching `int[]` (one code point per
slot in `LispString.chars`), so `(setf (schar s i) (code-char cp))` writes one
indexed slot on every backend for any code point in `[0, 0x10FFFF]`.

Notes on the JVM representation: previously chars were `java.lang.Character`
(16-bit `char`), so `#\U+1F600` truncated silently to a lone surrogate. Widening
to `int[]{cp}` was done together with the string-indexing code-point work in
todo 153 -- see [[wasm-gc-strings]] for the sibling widening on the WASM string
byte model that closed the same todo on the WASM side. The JVM discriminator is
disjoint from `Object[]` (cons / function value), `BigInteger[]` (ratio),
`double[]` / `float[]` (packed float arrays), so `_eqv` / `_eq` /
`characterp` / instanceof-based dispatch stay unambiguous. Because Java does not
cache `int[]` literals the way `Character.valueOf(char)` caches 0..127, `_eqv`
now has a first-branch check for both-int[] operands and compares by `arr[0]`
so `(eq (code-char 65) #\A)` still returns `T`.

## String indexing is by code point

`length` / `char` / `schar` / `aref` (rank-1 string) / `subseq` / `elt` /
`position` / `search` / `find` / `mismatch` / `string-capitalize` all walk their
argument BY CODE POINT, not by UTF-16 code unit. A supplementary code point
(above `U+FFFF`) counts as one indexed character, so `(length "😀") == 1` and
`(char "aé😀b" 2) == #\😀` on every backend.

- **Interpreter** (`Environment.java`) -- `LENGTH` reads `str.codePointCount()`
  (equivalent to `length()` under the new int[] storage), `charRef` reads
  `LispString.codePointAt(i)` -- ONE array slot -- , `sequenceRef` walks
  `String.offsetByCodePoints(0, i)` +
  `String.codePointAt(codeUnit)` on the reassembled `value()`, `SUBSEQ` translates
  a character range to a code-unit range through the same walk, `seqAsList` builds
  one `LispChar` per code point via `String.codePointBefore(codeUnit)`.
  `capitalizeString` and `trimString` walk by code point via
  `Character.isLetterOrDigit(int)` and `Character.toUpperCase(int)` /
  `toLowerCase(int)`. Mutation (`%schar-set`, `storeStringChar`,
  `vectorPushExtend`) writes one code point into one slot -- no BMP-only reject.
- **JVM compile path** -- `JvmLengthRuntimeBuilder` returns
  `str.codePointCount(1, length()-1)` on the string branch (the framing quotes
  live at 0 and `length-1`). `JvmCharCompiler.compileChar` and
  `JvmArrayRuntimeBuilder._aref1`'s string branch call
  `String.offsetByCodePoints(1, index)` -> `String.codePointAt(codeUnit)` and
  box as `int[]{cp}`. `JvmSubseqCompiler`'s string arm translates its bounds
  the same way. `JvmStringCapitalizeCompiler` walks code points and appends
  via `StringBuilder.appendCodePoint(int)`. `_strv` (the mutable char-vec
  normalizer) reads each element as `int[]` and appends via `appendCodePoint`
  so a supplementary code point expands to its two-unit UTF-16 pair.
- **WASM (both backends)** -- see [[wasm-gc-strings]]. The `_charvec_to_str`
  builder emits each code point as its 1-4 byte UTF-8 sequence; `_str_char_count`
  counts UTF-8 lead bytes, `_str_char_at` decodes the sequence at a walked
  position, `_str_char_byte_offset` translates a character index to a byte
  offset for `subseq`. Length / char / subseq lower through those helpers.

**COST: uniform now, and it was not.** A character index is O(1) or amortized
O(1) on all four backends, so a left-to-right `dotimes` scan is LINEAR
everywhere -- the WASM string carries a character-index cursor and the JVM proves
its string free of surrogate pairs and remembers that. The full mechanism, the
measurements and what is still not constant: [[string-index-cost]].

Before that, a character index was O(i) on all three compile backends (the JVM's
`offsetByCodePoints` and wasm's UTF-8 decode both walked from the start), which
made a scan quadratic -- 195 ms (JVM) / 1045 ms (wasm P1) / 981 ms (component)
over one 48,000-character literal against the interpreter's 23. Two consequences
of that era are still worth knowing: the interpreter used to be the WORST of the
four (632 ms at index 0, because `charRef` rebuilt the whole Java `String` from
the `int[]` on every call, then counted its code points, then walked to the
index), and generated bulk data was cut into MANY SHORT string literals to keep
each scan bounded -- `eval/Uax15Tables` still cuts its derived runs at 1,000
characters ([[asdf]]), which was worth ~15x on either WASM backend and ~10x on
the JVM over a 56 KB run and is worth nothing now.

## String comparison family = ONE code-point walk

`string<` / `string>` / `string<=` / `string>=` / `string/=` and their
case-insensitive counterparts `string-lessp` / `string-greaterp` /
`string-not-greaterp` / `string-not-lessp` / `string-not-equal` are ten
one-liners over a single shared helper, the `LispPreludeLibrary` defun
`%string-compare`, which returns `(order . mismatch-index)`: `order` is `-1`/`0`/`1`
for substring1 before/equal/after substring2, and `mismatch-index` is the index
**into string1** of the first differing character -- `end1` when the substrings
are equal, which is exactly what `string<=` / `string>=` /
`string-not-greaterp` / `string-not-lessp` must return for equal strings. Being
one rontolisp-source defun, the walk IS the same code on all four backends, and
because it steps with `char` / `char<` / `char-downcase` it is code-point-based
and full-Unicode case-folding for free (`(string< "あい" "あう")` = 1 everywhere).
It is iterative (a `while` over a `result` accumulator), not recursive, so
comparing long strings cannot exhaust the stack on any backend.

`string=` / `string-equal` are the exception: they stay per-backend intrinsics
(`Environment` + `Jvm/WasmStringEqCompiler`) because two-argument string equality
is hot everywhere. Their `:start1`/`:end1`/`:start2`/`:end2` shape is therefore
handled twice, deliberately: the interpreter parses the keywords in Java
(`Environment.boundedStringArg`, code-point bounds like `subseq`'s), and both
compilers lower the call onto `subseq` first
(`LispMacroExpander.expandStringComparisonBounds`, dispatched from
`Jvm/WasmExprCompiler` only when `hasStringComparisonBounds`), so the intrinsic
still always sees two plain strings and a keyword-free call compiles
byte-identically to before. The `#'string=` / `#'string-equal` first-class value
needs the same treatment a third time: their `BuiltinFunctionWrappers` entries
(`stringEquality`) are `(a b &rest kw)` and re-extract the bounds with `getf`,
falling back to the direct two-argument call when `kw` is nil -- without that,
`(apply #'string= a b :start1 1)` would silently ignore the keywords on the
compile paths while the interpreter honoured them. The ordering predicates need
none of this: they are ordinary defuns whose lambda list takes the four keywords.

One consequence for the compile path: a program calling `string>` never
mentions `%string-compare`, so `LispPreludeLibrary.process` selects the prelude
entries to splice **to a fixpoint** (a pulled-in definition drags in whatever it
references). The interpreter gets this for free -- it resolves prelude names
lazily, one call at a time.

Pinned by the `string-comparison-family` ci-spec case (all four backends),
`LispEvaluatorTest#evalStringOrderingPredicates` (+ the case-insensitive /
bounding-index / designator siblings),
`JvmLispCompilerTest#compileAndRunStringOrderingPredicates` and
`WasmLispCompilerIntegrationTest#stringOrderingPredicates`.

## Case fold is FULL Unicode, and it is PER CODE POINT everywhere

`char-upcase` / `char-downcase` produce the same code point
`Character.toUpperCase(int)` / `Character.toLowerCase(int)` would on the same
JVM Unicode baseline.

**`string-upcase` / `string-downcase` / `string-capitalize` apply that same
single-code-point fold to EVERY character** -- which is exactly how CLHS defines
them ("each character of the result string is produced by applying the function
`char-upcase` to the corresponding character of string"). Two consequences that
are load-bearing, not incidental:

- The result has the SAME CHARACTER COUNT as the argument. There is no
  multi-character special casing: `(string-upcase "straße")` is `"STRAßE"`, not
  `"STRASSE"` (SBCL agrees).
- No context-sensitive rule applies: `(string-downcase "ΑΣ")` is `"ασ"`, not the
  final-sigma `"ας"`.

Both interpreter and JVM used to call `String.toUpperCase(Locale.ROOT)` /
`toLowerCase(Locale.ROOT)`, which does apply the 102 upper / 1 lower
SpecialCasing expansions AND the Final_Sigma rule -- so *those two* were the
backends deviating from CL, while WASM deviated in the other direction by
folding ASCII only. Both sides were corrected together (todo 267); do not
"simplify" either one back to the `String` overload.

- **Interpreter** -- `Environment.caseFoldString` walks code points and applies
  `Character.toUpperCase(int)` / `toLowerCase(int)`;
  `Environment.capitalizeString` does the same with the
  `Character.isLetterOrDigit(int)` word test.
- **JVM compile path** -- one shared emitter, `JvmStringCaseFold`, walks the
  quoted designator by `String.codePointAt` / `Character.charCount` and appends
  through `StringBuilder.appendCodePoint(int)`. `JvmStringUpcaseCompiler` and
  `JvmStringCapitalizeCompiler` are thin mode selectors over it. The framing
  quote bytes are neither cased nor alphanumeric, so they survive the walk.
- **WASM (both backends)** -- `_char_upcase` and `_char_downcase` runtime
  helpers (`WasmCaseFoldRuntimeBuilder`) binary-search a compressed
  `(from:u32, to:u32, delta:i32)` range table baked into the static data
  segment. The tables are generated from `Character.toUpperCase(int)` /
  `Character.toLowerCase(int)` at compile time, so every mapping the
  interpreter and the JVM know about is present. Cache: ~16 KB combined
  (upper 690 ranges, lower 674 ranges at Unicode 15) with 10-deep binary
  search per call.
  `_string_upcase` / `_string_downcase` / `_string_capitalize`
  (`WasmStringRuntimeBuilder.emitCaseFoldCore`) DECODE each 1-4 byte UTF-8
  sequence, call those same two helpers on the code point, and re-encode --
  they must never fold bytes. `_string_capitalize` finds its word boundaries
  with a third helper, `_char_alnum_p` (FUNC_CHAR_ALNUM_P), the same binary
  search over a `(from, to)` PAIR table generated from
  `Character.isLetterOrDigit(int)` (728 ranges / ~5.8 KB at Unicode 16); an
  ASCII alnum test would restart a word after a caseless letter and answer
  `"AあB"` where the other backends answer `"Aあb"`.
  All three tables are their own `WasmTreeShaker.OwnedDataSegment` owned by
  their sole reader, so `--optimize` drops each with its helper.

**Output-buffer sizing on WASM is derived, not assumed.** A fold can WIDEN a
code point's UTF-8 encoding (`U+0250` upcases to `U+2C6F`: two bytes in, three
out), so `emitCaseFoldCore` grows the scratch to
`inputBytes * (1 + WasmCaseFoldRuntimeBuilder.maxUtf8Growth()) + 2`.
`maxUtf8Growth()` is computed from the baked tables at compile time (1 today),
which is a valid multiplier because a code point occupies at least one input
byte -- a future JDK Unicode baseline that folds more widely widens the bound
with it instead of silently overrunning the scratch.

`--no-gc` is not a fifth opinion here: it rejects `string-upcase` /
`string-downcase` / `string-capitalize` as ineligible operations at
`collectCalls`, so there is nothing to keep in step.

Still ASCII-only on WASM and therefore still divergent: `alpha-char-p`
(`WasmCharCompiler.compileAlphaCharP`) and the `string-equal` / `char-equal`
case-insensitive compare (`WasmStringRuntimeBuilder.emitMaybeLower`), where the
interpreter uses `Character.isLetter(int)` and `equalsIgnoreCase`. Those need
their own membership table (`isLetter`) and a fold-based compare; tracked
separately, not closed by todo 267.

## Print / read

- `princ` on a CHARACTER prints its glyph via `Character.toString(int)`
  (interpreter, JVM) or its 1-4 byte UTF-8 sequence to stdout / capture buffer
  (WASM: `emitPrintChar.emitGlyph` -> `_write_str`).
- `prin1` on a CHARACTER prints `#\Space` / `#\Newline` / ... for the standard
  non-graphic set (see `LispChar.name` on the interpreter, `_charPrin1` on the
  JVM, `emitPrintChar` on the WASM) and `#\<glyph>` otherwise.
- A `#\` reader token in source is read as a code point directly (the reader
  decodes UTF-8 in source verbatim).
- `read-char` at runtime returns a CHARACTER holding a full code point on every
  backend. Interpreter and JVM: a `BufferedReader.read()` high-surrogate code unit
  is combined with the peeked low half via `mark(1)` + conditional `reset()` before
  boxing. WASM (both backends): `_read_char` decodes a 1-4 byte UTF-8 sequence at
  the cursor by dispatching on the lead byte's high bits (the same
  `<0x80 / <0xE0 / <0xF0 / else` ladder `_str_char_at` uses); the string-stream
  branch clamps against the buffer end, the WASI fd branch clamps against
  successive `fd_read` yields (a truncated tail falls back to the lead byte as a
  bare CHARACTER on both paths, matching the interpreter's non-surrogate follow
  behaviour). A supplementary code point round-trips as one CHARACTER through
  `(with-input-from-string ... (read-char ...))` on all four backends
  byte-identically.

## Java interop (JVM compile path)

`JavaBridgeTemplate` marshals / unmarshals CHARACTER at the reflection
boundary:

- **In (Lisp -> Java)** -- a length-1 `int[]{cp}` argument coerces to
  `char` / `Character` when the target parameter is char-typed AND the code
  point fits in the BMP (`Character.isBmpCodePoint(cp)`). It also coerces to
  `int` / `Integer` for parameters of that type; a supplementary code point
  bridges through the `int` path only.
- **Out (Java -> Lisp)** -- a `java.lang.Character` return value becomes
  `new int[] { c.charValue() }`, so downstream Lisp ops see a proper CHARACTER
  without a truncation seam.

## Mutation semantics

`(setf (aref s i) ch)` / `%schar-set` on a mutable string (`make-array N :element-type 'character`)
stores one CHARACTER per slot on every backend: the interpreter's `LispString`
holds `int[]` (one code point per slot), the JVM char-vec holds `int[]{cp}` per
ArrayList slot (normalized to a String at read-out via
`StringBuilder.appendCodePoint`), and WASM holds `TYPE_CHAR` per slot. A
supplementary code point via setf-aref lands in exactly one indexed slot on
every backend and prints as its glyph. Capacity, fill pointer and index are all
in code-point units. On the two compile paths the write itself goes through the
shared `%schar-set-runtime` defun rather than being expanded at the site --
semantics unchanged, `.kb/string-write-runtime.md` for why.

`read-char` is code-point-symmetric across all four backends now (interpreter
and JVM combine UTF-16 surrogate pairs via `mark(1)`/`reset()`; WASM decodes
1-4 byte UTF-8 sequences in `_read_char`).

## eq on CHARACTER = char= (implementation-defined but pinned)

CL permits `eq` on two `char=` characters to return `T`, and every backend now
takes that permission. Each rep is a value object, so two allocations with the
same code point compare equal even though they are not the same reference:

- **Interpreter** -- `LispChar` is a record; its derived `equals` is
  value-based and the `eq` primitive walks through it for a `LispChar` operand.
- **JVM compile path** -- `_eqv` (the shared value-equality helper `eq`
  delegates to via `_eq`) has an early-branch for two length-1 `int[]` operands
  that compares by `arr[0]`, restoring the T that the pre-widening
  `Character.valueOf(char)` cache produced for BMP code units.
- **WASM (both backends)** -- `emitEqComparison` in `WasmEmitHelper` follows a
  `ref.eq`-false miss with a `ref.test $type_char` guard on both operands, and
  when it hits compares the code-point field. `emitEqlComparison` uses the
  same shared helper (`emitCharCodePointEqOrElse`), so both `eq` and `eql`
  give TYPE_CHAR value equality without duplicating the char-compare shape.
  `_equal` (`WasmRuntimeBuilder.buildEqualBody`) already had its own
  TYPE_CHAR branch for structural equality.

`(eq (code-char cp) (code-char cp))` returns `T` on every backend as a result;
`(eq (code-char 128512) #\A)` returns `NIL` (different code points).

## Pinning tests

- `src/test/resources/ci-spec.yaml` -- `code-point-characters-beyond-ascii`
  case, run once per backend by `CiSpecE2eTest` against the native binary.
  Covers `code-char` / `char-code` round-trip on Latin-1 supplement, Greek,
  Cyrillic and astral (`U+1F600`); the case-fold table on all three of those
  scripts; string `length` / `char` / `subseq` on a mixed-BMP-and-astral
  string; the read-side predicates on ASCII; the mutation round-trip
  (`(setf (schar s i) (code-char 128512))` + `vector-push-extend`) landing a
  supplementary code point in one indexed slot on every backend; and the
  `(with-input-from-string ... (read-char ...))` round-trip that returns one
  CHARACTER per code point on every backend.
- `src/test/resources/ci-spec.yaml` -- `eq-on-characters-by-code-point` case
  pins `(eq #\A #\A)` / `(eq (code-char 65) #\A)` /
  `(eq (code-char 128512) (code-char 128512))` all to `T` on every backend
  (including a `let`-bound cp defeating any constant folding of the two
  allocations), and `(eq (code-char 128512) #\A)` to `NIL`. Also pins that
  `eq` on freshly-consed cells and on two boxed floats stays `NIL` -- the
  new TYPE_CHAR branch adds no extra value equality for non-chars.
- `src/test/resources/ci-spec.yaml` -- `string-case-ops-full-unicode` case pins
  `string-upcase` / `string-downcase` / `string-capitalize` on Latin-1, Greek,
  Cyrillic and astral (Deseret) input, the length-preserving sharp s and
  non-context final sigma, the two UTF-8-widening folds (`U+0250`, `U+023A`),
  and the full-Unicode word constituents -- byte-identical on all four
  backends.
- `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`
  cover per-backend char builtins and the WASM `_char_upcase` /
  `_char_downcase` helper indices;
  `WasmLispCompilerIntegrationTest#eqOnCharactersComparesByCodePoint`
  guards the WASM emit path directly, and
  `#stringCaseOpsAreFullUnicode` / `#stringCapitalizeWordConstituentsAreFullUnicode`
  guard the code-point walk and the `_char_alnum_p` word test. The
  per-backend siblings are
  `LispEvaluatorTest#evalStringCaseOpsFoldEveryCharacterIndependently` /
  `#evalStringCapitalizeIsFullUnicode` and
  `JvmLispCompilerTest#compileAndRunStringCaseOpsAreFullUnicodeAndLengthPreserving`.

## Related
- [[string-index-cost]] -- what makes those walks amortized O(1) on every
  backend (the WASM per-string cursor, the JVM surrogate-pair proof).
- [[wasm-gc-strings]] -- the WASM byte model behind these code-point walks
  (todo 159), and the mem-module-min-pages patch that unblocked uax-15's
  static UnicodeData.
- [[reader-case-upcase]] -- the reader's uppercase-canonical fold (a different
  case fold: on SYMBOL names, not CHARACTER values).
