# `sort` is one merge sort, shared by every backend

**Invariant: `sort` is O(n log n) on all four backends and it is the SAME merge sort -- same
split, same question to the predicate -- so the four answer one permutation for one input.**

Two homes, which must change together: `LispMacroExpander.sortRuntimeWrapper()` (the
`%sort-runtime` defun injected per program by `JvmLispCompiler`/`WasmLispCompiler`; every
compiled `sort` site is `(%sort-runtime list predicate)`) and `LispEvaluator.sortValues`.

## Algorithm
1. Cut at the middle by two-pointer walk, LEFT half longer on odd length (`sortValues` splits
   at `from + (length + 1) / 2`).
2. Sort both halves.
3. Merge, asking `(pred right left)` ONCE per step, taking LEFT unless true.

Stability is a FACT, not a promise: the docs say equal-element order is unspecified, and
`stable-sort` decorates with indices (`LispMacroExpander.expandStableSort`) and calls `sort`.

## Notes and traps
- After the call the INPUT list differs (undefined in ANSI, unpinned): compile paths relink
  the argument's cells with `rplacd`; the interpreter builds a fresh list. Use the return value.
- Injection gate `LispMacroExpander.programUsesSort` is the BACKEND's, not
  `expandTopLevelDefinitions`', because the `#'sort` wrapper does not exist until the backend
  generates it. No array gate: the body is `car`/`cdr`/`rplacd` + `funcall`.
- `JvmSortCompiler`/`WasmSortCompiler` (inline SELECTION sorts) survive as a fallback reachable
  only by a program that DEFINES `%sort-runtime` itself -- correct, quadratic, differently
  permuting.
- **Wasm name registry is armed at the SITE.** A symbol designator resolves only through it,
  and injected runtime bodies are excluded (`Ctx.injectedRuntimeBody`), so `WasmExprCompiler`'s
  `SORT` case arms it itself (`Ctx.runtimeDesignatorDispatch`). Without that the program traps
  on `unreachable` in the arity-2 dispatcher.

## Tests
- ci-spec `sort-is-linearithmic-and-stable` (all four; 20,000 elements + repeated keys).
- `LispEvaluatorTest.evalSortOfALargeListIsLinearithmicAndOrdersEqualElementsLikeEveryOtherBackend`
  (100,000, 30 s timeout), and the same method name on `JvmLispCompilerTest` /
  `WasmLispCompilerIntegrationTest` (50,000, `compileAndRun` prefix on the JVM).
- `LispMacroExpanderTest.theSortRuntimeIsOneMergeSortEveryCompiledSortSiteCalls`.
