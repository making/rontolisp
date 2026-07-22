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
  (added to `LispString`), `charRef` / `sequenceRef` walk `String.offsetByCodePoints(0, i)` +
  `String.codePointAt(codeUnit)`, `SUBSEQ` translates a character range to a
  code-unit range through the same walk, `seqAsList` builds one `LispChar` per
  code point via `String.codePointBefore(codeUnit)`. `capitalizeString` and
  `trimString` walk by code point via `Character.isLetterOrDigit(int)` and
  `Character.toUpperCase(int)` / `toLowerCase(int)`.
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

## Case fold is FULL Unicode

`char-upcase` / `char-downcase` produce the same code point
`Character.toUpperCase(int)` / `Character.toLowerCase(int)` would on the same
JVM Unicode baseline. `string-upcase` / `string-downcase` delegate to Java's
locale-neutral `String.toUpperCase(Locale.ROOT)` / `toLowerCase(Locale.ROOT)`
which already understands supplementary code points and returns the same
result.

- **Interpreter and JVM compile path** -- `Character.toUpperCase(int)` /
  `Character.toLowerCase(int)` directly. These are single-code-point
  transformations; a mapping that would expand (e.g. German sharp s) lives on
  the `String` overload and does not surface at `char-upcase`.
- **WASM (both backends)** -- `_char_upcase` and `_char_downcase` runtime
  helpers (`WasmCaseFoldRuntimeBuilder`) binary-search a compressed
  `(from:u32, to:u32, delta:i32)` range table baked into the static data
  segment. The tables are generated from `Character.toUpperCase(int)` /
  `Character.toLowerCase(int)` at compile time, so every mapping the
  interpreter and the JVM know about is present. Cache: ~16 KB combined
  (upper 690 ranges, lower 674 ranges at Unicode 15) with 10-deep binary
  search per call.

## Print / read

- `princ` on a CHARACTER prints its glyph via `Character.toString(int)`
  (interpreter, JVM) or its 1-4 byte UTF-8 sequence to stdout / capture buffer
  (WASM: `emitPrintChar.emitGlyph` -> `_write_str`).
- `prin1` on a CHARACTER prints `#\Space` / `#\Newline` / ... for the standard
  non-graphic set (see `LispChar.name` on the interpreter, `_charPrin1` on the
  JVM, `emitPrintChar` on the WASM) and `#\<glyph>` otherwise.
- A `#\` reader token in source is read as a code point directly (the reader
  decodes UTF-8 in source verbatim).
- `read-char` at runtime returns a CHARACTER holding whatever integer the
  underlying stream produced: on the interpreter and the JVM it walks Java's
  UTF-16 code-unit stream, so a supplementary code point surfaces as a lone
  surrogate; on WASM the binary stream returns one UTF-8 byte at a time. Not
  a defect at the character type; the widening (combine surrogate pairs
  / decode multi-byte UTF-8) is `.todo/161`.

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
stores one CHARACTER per slot on the JVM (`int[]{cp}` per ArrayList slot,
normalized to a String at read-out via `StringBuilder.appendCodePoint`) and
on WASM (`TYPE_CHAR` per slot). The interpreter still stores `char[]` under
the hood, so a supplementary code point via setf-aref is REJECTED (`storeStringChar`
in `Environment.java`). The read path (`length` / `char` / `subseq` / ...)
is code-point-visible on every backend regardless.

This mutation asymmetry -- interpreter rejects, JVM/WASM accept -- is one of
three remaining "not-all-four-backends" gaps after todo 153; widening the
interpreter's `LispString` backing store to `int[]` (one code point per slot)
is the fundamental fix, deferred to `.todo/160`. The other two:

- `read-char` returns a lone UTF-16 surrogate on the interpreter / JVM and one
  UTF-8 byte on WASM instead of one CHARACTER holding the full code point --
  see `.todo/161`.
- `(eq (code-char cp) (code-char cp))` returns T on the interpreter and the
  JVM compile path but NIL on WASM (implementation-defined by CL, but a
  byte-identical divergence) -- see `.todo/162`.

Programs that stick to BMP-only mutable strings and eq-only-for-identity (not
value) round-trip identically on every backend today.

## Pinning tests

- `src/test/resources/ci-spec.yaml` -- `code-point-characters-beyond-ascii`
  case, run once per backend by `CiSpecE2eTest` against the native binary.
  Covers `code-char` / `char-code` round-trip on Latin-1 supplement, Greek,
  Cyrillic and astral (`U+1F600`); the case-fold table on all three of those
  scripts; string `length` / `char` / `subseq` on a mixed-BMP-and-astral
  string; the read-side predicates on ASCII.
- `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`
  cover per-backend char builtins and the WASM `_char_upcase` /
  `_char_downcase` helper indices.

## Related
- [[wasm-gc-strings]] -- the WASM byte model behind these code-point walks
  (todo 159), and the mem-module-min-pages patch that unblocked uax-15's
  static UnicodeData.
- [[reader-case-upcase]] -- the reader's uppercase-canonical fold (a different
  case fold: on SYMBOL names, not CHARACTER values).
