# uax-15 v0.1.3 on the WASM compile paths (JVM already GREEN)

Parent: `.todo/154` (cl-postgres dependency-library grind). The interpreter and
JVM are green (`Uax15E2eTest#loadsAndRunsOnTheInterpreter`,
`Uax15E2eTest#compilesAndRunsOnJvm`); both WASM backends remain @Disabled with
a rationale referencing this file. What follows is the piece that survives:
the WASM string model does not carry non-BMP code points, so uax-15's
`unicode-string` scratch values truncate on serialization.

## What already landed (JVM + interpreter)

Committed together in this pass; the changes are entirely on the compile path
so the interpreter is untouched. The seed shape is
`src/test/resources/uax-15/src/precomputed-tables.lisp` line 5-7, 13, 52 +
`uax-15.lisp` line 53 (four load-time pathname primitive uses composed with a
34k-line `with-open-file`).

- `cli/CompileTimePathnameFolder` -- a post-`spliceSystem` walker on top of
  `LoadInliner.inline`. Folds `(asdf:find-system 'NAME [nil])` to `"name"`,
  `(asdf:system-source-directory X)` to `<baseDir>/`,
  `(make-pathname :directory ...)` to a namestring, and
  `(uiop:merge-pathnames* A B)` to the merged namestring, when every argument
  reduces to a literal string / symbol / keyword. A top-level `(defparameter
  *X* <literal string>)` records the substitution so later forms of the same
  compile unit fold references to `*X*` too. A `(quote DATUM)` is opaque so
  quoted data is never rewritten.
- Same class also rewrites `(with-open-file (VAR <literal utf-8 path>
  [:external-format :UTF-8]) BODY...)` as `(with-input-from-string (VAR
  <inlined file contents>) BODY...)` when the literal path names a UTF-8 file
  that exists at compile time. Contents past ~20k Java chars are split at
  code-point boundaries and reassembled at runtime through `(concatenate
  'string CHUNK1 ...)` so the JVM's 65535 UTF-8 byte per-string ceiling is
  never crossed (uax-15's 1.9 MB `UnicodeData.txt` becomes ~64 chunks).
- `LispMacroExpander.expandReadLineCompat` -- lowers `(read-line stream nil
  [eof-value])` to `(read-line stream)` (or `(or (read-line stream) eof-val)`
  when the eof-value is not literal nil); the per-backend `JvmReadLineCompiler`
  / `WasmReadLineCompiler` call it before their argcount check.
- `LispMacroExpander.expandSubseqCompat` + `LispNames.SUBSEQ_CORE` -- the
  public `subseq` dispatches at runtime on `%arrayp`. A vector is copied
  element-by-element through `make-array` + `aref` + `%aset` (fresh vector
  result, same length semantics as the interpreter's `Environment.SUBSEQ`);
  everything else routes through `%SUBSEQ-CORE`, the pre-existing
  string/list-only dispatch each per-backend subseq compiler kept.
- `LispMacroExpander.seqAsListForm` collapsed to `(coerce X 'list)`. The
  coerce macro already dispatches on `listp` / `stringp` / else, so vectors
  now scan through `aref` + `length` instead of the old `(if (stringp x)
  (coerce ...) x)` shape that fell through to CAR/CDR on a vector.
- `JvmArrayCompiler.compileMake` -- `(make-array N :element-type 'character)`
  without `:fill-pointer` / `:adjustable` no longer lowers to the immutable
  `make-string`; it routes to `_charVecMake` with an implicit fill-pointer
  = capacity so `setf-aref` writes land in place. The WASM equivalent is
  intentionally NOT changed (see below).

Verification: `Uax15E2eTest#loadsAndRunsOnTheInterpreter` +
`Uax15E2eTest#compilesAndRunsOnJvm` green, all four normalize forms produce
the exact expected code-point sequences. Regression guards `ClPpcreE2eTest`,
`ParseNumberE2eTest`, `LoadInlinerTest` (with 8 new fold cases) all green.

## What remains (both WASM backends)

The WASM string model is byte-oriented. `_charvec_to_str` in
`WasmStringRuntimeBuilder.buildCharvecToStrBody` -- the function every WASM
consumer that treats a mutable character vector as a string routes through
(`stringp`, `char`, `schar`, `subseq`, printing, `=`, `_eqv`) -- writes each
element as a SINGLE BYTE via `I32_STORE8`. uax-15 stores non-BMP scratch
values in `unicode-string` (a `(vector unicode-point)`) during
`from-unicode-string`, so `(char lisp-string i)` on WASM reads a truncated
code point: expected 197 (Å), actual 65 (A). Every downstream read that
goes through `char` / `stringp` sees the same truncation, so the compiled
binary silently produces wrong output.

Two viable directions:

**A. Widen `_charvec_to_str` to UTF-8** (recommended, keeps WASM's byte-string
model). Each stored code point emits 1 to 4 bytes; the buffer growth
allocation in `buildCharvecToStrBody` picks the worst case (`n * 4`). Then
`char` / `schar` / `_subseq` on the resulting TYPE_STRING decode UTF-8 back
to code points on read -- and every backend consumer of a string byte array
gets the same treatment. Not a small change: the WASM string byte array's
"length in code points" and "length in bytes" diverge everywhere they used
to be interchangeable. Pinning tests need to cover both an all-ASCII and a
mixed BMP+supplementary case.

**B. Keep the byte-string model and make aref/char on a char-vec bypass the
string conversion** (smaller, but semantically odd). `(aref char-vec i)`
already reads the raw TYPE_CHAR from the data slot; `(char char-vec i)`
would have to be rewritten to detect a char-vec target and use the same
raw read. `elt`'s dispatch (`(if (stringp seq) (char ...) ...)`) then
misroutes through the truncating string branch -- the compat rewrite could
prefer aref on char-vecs. Also `map`/`concatenate`/every-other string
consumer that goes through `_charvec_to_str` needs its own handling. This
is a lot of "avoid the shared normalizer" plumbing scattered across the
WASM backend; the shared normalizer is the whole reason the truncation is
uniform in the first place.

Recommend A. The refactor is large enough to warrant its own todo split.

## Verification chain (after A lands)

1. `./mvnw test -Dtest=Uax15E2eTest` -- all four backends green (delete the
   two `@Disabled` overrides at the bottom of `Uax15E2eTest`).
2. `./mvnw test -Dtest=ClPpcreE2eTest` -- LOOP + subseq regression guard.
3. `./mvnw test -Dtest=ParseNumberE2eTest` -- LOOP regression guard.
4. `./mvnw test` -- full suite (~10 min); watch for any WASM printing case
   that now emits multi-byte sequences.
5. Manual four-backend spot-check per `CLAUDE.md`: interpreter, JVM,
   Preview 1, Component, on a tiny `(uax-15:normalize ...)` script under
   `--system-path` to the vendored `src/test/resources/uax-15`.
6. Native E2E if `ci-spec.yaml` corpus grew a char-vec case.

## Files to touch when A begins

- `src/main/java/am/ik/rontolisp/codegen/wasm/WasmStringRuntimeBuilder.java`
  -- `buildCharvecToStrBody` (encoder) and its consumers (`_subseq`,
  `_char_star`, printer).
- `src/main/java/am/ik/rontolisp/codegen/wasm/WasmCharCompiler.java`
  -- `char`/`schar` reads decode UTF-8; `code-char` writes multi-byte.
- `src/main/java/am/ik/rontolisp/codegen/wasm/WasmArrayCompiler.java`
  -- revive the JVM-side "always char-vec for character element type"
  branch under the widened model.
- `src/test/java/am/ik/rontolisp/e2e/Uax15E2eTest.java` -- delete the two
  `@Disabled` overrides.
- `.kb/asdf.md` -- flip uax-15's status; update the "all four backends"
  paragraph.

## Non-goals

- General runtime pathname support (`pathname` values distinct from strings).
- General filesystem access from the WASM sandbox (bundling is already the
  answer for compile-time-known files).
- Fixing the byte-string assumption in the WASM printer's numeric or
  s-expression paths (they are ASCII by construction; only the char-vec
  serialization needs widening).
