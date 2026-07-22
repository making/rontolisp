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
  (equivalent to `length()` under the new int[] storage), `charRef` /
  `sequenceRef` walk `String.offsetByCodePoints(0, i)` +
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
in code-point units.

The other cross-backend gap after todo 160 / 161:

- `(eq (code-char cp) (code-char cp))` returns T on the interpreter and the
  JVM compile path but NIL on WASM (implementation-defined by CL, but a
  byte-identical divergence) -- see `.todo/162`.

`read-char` is code-point-symmetric across all four backends now (interpreter
and JVM combine UTF-16 surrogate pairs via `mark(1)`/`reset()`; WASM decodes
1-4 byte UTF-8 sequences in `_read_char`).

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
- `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`
  cover per-backend char builtins and the WASM `_char_upcase` /
  `_char_downcase` helper indices.

## Related
- [[wasm-gc-strings]] -- the WASM byte model behind these code-point walks
  (todo 159), and the mem-module-min-pages patch that unblocked uax-15's
  static UnicodeData.
- [[reader-case-upcase]] -- the reader's uppercase-canonical fold (a different
  case fold: on SYMBOL names, not CHARACTER values).
