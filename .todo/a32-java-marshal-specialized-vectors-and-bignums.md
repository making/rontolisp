# java: does not marshal specialized vectors or bignums

Difficulty: Medium

Both backends agree (measured 2026-09-26), but the guide promises that "a rank-1 array
made with `make-array`" converts to a Java array or `List`, and it does not for a
specialized one:

- `(java:static "java.util.Arrays" "toString" (make-array 3 :element-type 'double-float))`
  (also `single-float`, `(unsigned-byte 8)`): `No matching method java.util.Arrays.toString`
  on both; `fixnum` and `bit` vectors convert.
- A Lisp bignum (or a ratio) matches no parameter: `(java:static "java.lang.String"
  "valueOf" (expt 2 100))` is `No matching method`, and no integer reaches a `BigInteger`
  parameter. A `BigInteger` result is unmarshalled into a Lisp integer, so the way back
  is missing.

Plan:
- Decide the kinds: a bignum kind (`BigInteger`, `Number`, `Object`, ...), `INTEGER` to
  `BigInteger` at some cost -- in `compiler/JavaOverloads.kindCost` (the one table), the
  bridge's hand copy (`JavaBridgeTemplateParityTest`), and the direct sites' conversions.
- Specialized vectors: the interpreter's `LispArray` variants and the compiled `double[]` /
  `float[]` / `byte[]` shapes with their headers (`.kb/jvm-export.md`, `.kb/packed-integer-vectors.md`)
  marshalled element-wise like any rank-1 vector, on all three paths.
- Pin on both backends through `testsupport/JavaInteropPrograms`; update the guide's table.
