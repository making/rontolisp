# m58. A compiled store checks its subscript before its value runs

Difficulty: Medium

`(setf (aref v 'x) (progn (princ "p ") 1))`: the interpreter prints `p ` and then reports
`(SETF AREF): The value X is not of type INTEGER`; the JVM (`JvmArrayCompiler.compileAset`,
`compileSubscript` checks where the subscript is evaluated) and both wasm backends report with
nothing printed. `ArefRankAndSubscriptChecksFixture.ORDER_SOURCE` states the interpreter's order
("every argument form is evaluated before any check; then each subscript's type, the array's,
its rank, a store's value and the bounds") but no row stores a non-integer subscript beside a
value that can be observed. Measured 2026-11-26 on the jar of develop at the time, both levels.

- Move each backend's subscript check after the value (and after every later subscript of a
  rank-n store); the JVM's fused store (`JvmIntFusionCompiler.classifyStore`) declines exactly
  this shape today and may then take it.
- Pin it with an `ORDER_SOURCE` row (every backend, every level).
