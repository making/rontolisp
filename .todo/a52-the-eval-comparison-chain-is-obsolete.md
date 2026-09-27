# Retire the compiled `eval`'s comparison chain

Difficulty: Low

`_eval` special-cases `= < > <= >= /=` (`JvmEvalRuntimeBuilder.comparisonChain`,
`WasmEvalRuntimeBuilder.emitComparisonChain`, `COMPARISON_OPERATORS`,
`SELF_COUNTED_OPERATORS`): it evaluates every argument and tests the pairs through the
wrapper's two-argument call. It exists because the wrappers took `(a b &rest r)` and the
generic registry path would have reported their count for `(eval '(< 1))`
(`.kb/eval-runtime.md`). Since 2026-09-26 the wrappers take `(a &optional b &rest r)`
(`BuiltinFunctionWrappers.comparison`), so the registry path answers every count the
chain answers.

Drop the arm on both backends, measure the eval-carrying class and module before and
after (`(print (eval '(+ 1 2)))`: 357,283 B `.class` / 262,201 B Preview 1 today), and keep
ci-spec `eval-wrong-arity-signals-program-error` /
`eval-inline-operators-check-their-argument-count` green -- the empty call's report must
still name the operator.
