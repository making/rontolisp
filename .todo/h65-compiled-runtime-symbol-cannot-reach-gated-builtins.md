# h65. A run-time symbol cannot reach a gated built-in or a car/cdr composition on the compile paths

Difficulty: Medium

Found 2026-10-31 working h39. On the JVM, P1 and the component, a symbol held in data
(`'((aref x) (cadr x) (typep x))`, an esrap-style rule table) answers `fboundp` NIL, and `symbol-function` / `funcall` of it signal `undefined-function`,
for 123 implemented `cl` functions (measured against SBCL 2.2.9's 636 external functions,
the 40 unimplemented ones excluded; the interpreter answers T and calls them):

- the `REFERENCE_GATED_FUNCTIONS` / `NATIVE_VALUE_FUNCTIONS` / complex-gated wrappers
  (`aref`, `typep`, `error`, `concatenate`, `sqrt`, `arrayp`, `class-of`, ...): injected only
  for a program that spells `(function NAME)` (`JvmLispCompiler` / `WasmLispCompiler`
  wrapper gates). With `#'aref` anywhere in the program, the data symbol works.
- the 28 `car`/`cdr` compositions: `#'cadr` compiles inline, so no named function exists to
  reach even when the program spells it.

Prelude defuns (`alphanumericp`, `equalp`) are not affected: a data mention splices them.

Plan: let a symbol constant in quoted data (`BuiltinFunctionWrappers.spellsSymbolConstant`)
count for the wrapper gates where the program can turn data into a call (a computed
`funcall`/`apply`/`symbol-function`/`fdefinition`/`fboundp`), and give the compositions a
named wrapper on the same gate. Keep a program that does neither byte-identical. Then
`fboundp`, `symbol-function` and `funcall` of such a symbol agree with the interpreter on all
four backends; extend `BoundpFunctionValueFixture` with `aref` and `cadr` held in data.
`.kb/symbol-runtime-api.md` ("Interpreter `fboundp` answers what a call would") holds the
measurement.
