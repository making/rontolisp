# A too-few-arguments call through `funcall` names the operator on every backend

Difficulty: Medium

Found 2026-09-26: `(funcall #'cons 1)` reports `CONS expects ...` on the interpreter but
`Function expects ...` on the compiled backends (JVM, wasm). The condition class matches
(`program-error`); the text does not.

Goal: the interpreter's shape -- the operator's name -- on all four backends, pinned in
`ci-spec.yaml`. Mind `.kb/error-handling.md` ("Argument-shape errors"): the JVM recovers
`program-error` from the `Function expects ` prefix in `JvmHandlerCaseCompiler.emitRawFailureTest`
(one entry of `LispMacroExpander.rawFailureConditionClasses()`), and the per-funcId arity table
exists because a per-callable string overflowed a method on the cl-postgres corpus
(`WasmRuntimeBuilder.ArityReport` on wasm). Keep that size bound; the name can come from the
funcId's symbol, which the tables already index.
