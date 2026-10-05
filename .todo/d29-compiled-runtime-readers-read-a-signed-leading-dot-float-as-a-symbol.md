# d29. The compiled runtime readers read `+.5` as a symbol

Difficulty: Low

Measured 2026-10-05: `(print (read-from-string "+.5"))` prints `0.5` on the interpreter and SBCL
and `+.5` (a symbol) on the JVM and both WASM backends; `+.5` in source compiles to the float
everywhere. `-.5` and `-.5e1` already read as floats. The cause is the leading-`+` strip in
`JvmReadRuntimeBuilder` (`_classify`) and the WASM twin in `WasmReadRuntimeBuilder`
(`_read_expr`): both drop the `+` only when a DIGIT follows, so a `.` followed by a digit is
left a symbol. `doc/{en,ja}/guides/read-load-limitations.md` lists `.5` with an optional leading
`+` as a float.

## Plan

- Drop the `+` also when it is followed by `.` and a digit, on both backends.
- Pin in ci-spec: `read-from-string` of `+.5`, `+.5e1`, `+.` (symbol), `+` (symbol), all four backends.

## Also measured

- `(read-from-string "5.)")` and `"5)"` signal `Unexpected ')'` on the interpreter and answer 5
  on SBCL and the compiled readers: a token ended by an unmatched `)` is read as the token. Decide
  whether the interpreter should follow.
