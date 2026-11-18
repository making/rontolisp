# j91. Rewrite what was ported from OpenJDK, outside the regex engine

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first** -- project-wide since 2026-11-18 (user
decision). The audit is `.todo/artefacts/j91-license-audit/README.md`; the numbers below are its rows. The regex engine is
j90's, not this item's.

OpenJDK is GPLv2 with the Classpath exception; the exception covers linking, not porting.
Each item below is embedded in users' compiled programs. Whoever works one does not read the
OpenJDK source of it and does not use the current code as the model: the current code is
deleted, the new one written from the specification, a paper, or a source under a licence
that allows it (rule 5: Apache-2.0, or a permissive licence with its notice kept), with a JDK
as a test oracle only.

- **#1 fdlibm on wasm** (`WasmFdlibmRuntimeBuilder`, transliterated from the JDK's Java
  port `java.lang.FdLibm`, `42ccdd859`): rewrite from Sun's C fdlibm 5.3 (its permission
  notice kept in the file and in `NOTICE`, j93). Bit-identity with `StrictMath` is the
  test, on both wasm backends.
- **#2 `Float.floatToFloat16`** (`WasmFloat16Compiler`, "a literal port"): from IEEE 754
  binary16 rounding as specified. Delete `.todo/artefacts/482-bfloat16-a-narrow-width-that-pays/Enc.java`.
- **#6 `BigInteger(String,int)`** in `clojure.lisp` (`%clojure-big-integer-new`): from
  the API's specified behaviour and messages observed under a JDK.
- **#7 `Formatter` checks** in `clojure.lisp` and `ClojureFormatLowering`: from the
  `Formatter` specification's flag table; no JDK method names.
- **To confirm and act on**: #20 Schubfach (`SchubfachTables`, `WasmSchubfachRuntimeBuilder`:
  establish whether it came from the JDK's `DoubleToDecimal` or Giulietti's original; if
  the JDK, rewrite from the paper or from the original under its licence), #24 `Random`'s
  `seedUniquifier` constants and `Hashtable`'s `max(2*size,11)` (not in the specification:
  choose our own and document the observable difference), #28 `%utf-8-malformed-length`
  (re-derive from the Unicode standard's well-formed table).
- Fix every `.kb` / comment that says "ported" from the JDK for these; record the outcome in
  the provenance file.
