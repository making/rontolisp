# d10. JVM: a string constant over 64 KB fails the compile

Difficulty: Medium

`am.ik.jvm.ConstantPool` refuses a `CONSTANT_Utf8` over 65,535 bytes, and the JVM backend
emits every string literal as one, so these programs compile on the interpreter and both
WASM backends but not with `-o X.class`:

- `(defvar *s* "<70,000 characters>")` -> `error: CONSTANT_Utf8 exceeds 65535 bytes: 70002`
- a package whose BAKED universe packs past 64 KB, e.g. `(defpackage :p (:use) (:export`
  4,800 names `))` (75,692 bytes), or a `defpackage` that `:use`s four packages of 4,000
  exports (199,562 bytes: the accessible universe carries the inherited externals). Found
  2026-10-04 building the walk-cost test, which therefore uses `make-package` for its wide
  package.

Fix direction: split a long constant into <= 65,535-byte chunks joined once at class init
(the modified-UTF-8 length counts a supplementary character as 6 bytes, so cut on code-point
boundaries by encoded length). Distinct from `.todo/017` (pool entry count / verifier).
