# h61. WASM refuses a reference to the REPL variable `*`, so trivial-types does not compile

Difficulty: Low

Found 2026-10-30 while closing h42.

```lisp
(ql:quickload :trivial-types)
(print (typep '(1 2 3) 'trivial-types:proper-list))
```

The interpreter and the JVM print `T`; both WASM targets fail the compile with
`Cannot compile symbol: *` (`WasmExprCompiler`). trivial-types' `%proper-list-p` macro
expands to `(or t (typep (car var) *))` when no element type is given, so its own
`proper-list-p` references the variable `*`. CL defines `*`/`**`/`***` (and `+`, `/`
families) as REPL variables, NIL outside a REPL. Make the WASM backends compile a reference
to them as the JVM does, on both WASM targets, with a ci-spec case.
