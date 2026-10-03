# c61. Clojure: `class`, `.printStackTrace` and `.getStackTrace` of an exception are refused

Difficulty: Low

Since 2026-10-03 an exception (`ex-info`, `(Exception. "m")`, a caught runtime error) is a
condition, which answers `.getMessage`/`.getLocalizedMessage`/`.getCause`/`.toString` only.
`(class e)` refuses (`class needs a value of a known kind`; before, a construction was a host
object on the interpreter and the JVM and answered its class), and `(.printStackTrace e)` --
common in `catch` bodies -- is a `java:call` refusal (interpreter/JVM) or a call-time error
(wasm). Plan: `class` of an exception answers its class name in the shape `class` answers
for other kinds (decide keyword vs host class object per backend), `.printStackTrace`
writes the `toString` line to `*err*` (the oracle adds `\tat` frames), `.getStackTrace` an
empty vector; measure each against `clj` 1.12.6, all four backends, pin in clojure-spec.
