# `sort` is one merge sort, shared by every backend

**Invariant: `sort` is O(n log n) on all four backends and it is the SAME merge sort --
same split, same question to the predicate -- so the four answer one permutation for one
input. No backend carries a sort of its own algorithm.**

Two homes: `LispMacroExpander.sortRuntimeWrapper()` (the `%sort-runtime` defun, Lisp
source, injected per program by `JvmLispCompiler`/`WasmLispCompiler`; every compiled `sort`
site is `(%sort-runtime list predicate)`) and `LispEvaluator.sortValues` (same algorithm in
Java over an array, for the interpreter).

## Algorithm

1. Cut at the middle cell by two-pointer walk (`fast` advances twice per step), LEFT half
   longer on odd length -- `sortValues` splits at `from + (length + 1) / 2`, same boundary.
2. Sort both halves.
3. Merge, asking `(pred right left)` ONCE per step, taking the LEFT element unless true.

Step 3 makes it stable; 1-3 make the permutation a function of the predicate's answers
alone, so an inconsistent predicate still agrees across backends. **Change the split or the
tie rule in one home and you must change the other.**

Stability is a FACT, not a promise: `doc/*/reference/functions/sort.md` says equal-element
order is unspecified, and `stable-sort` keeps its own index decoration
(`LispMacroExpander.expandStableSort`) -- it sorts the decorated list by calling `sort`, so
it inherits O(n log n); the two differ only in the promise and in `:key`.

## After the call the INPUT list differs (undefined in ANSI, unpinned)

- compile paths: relinks the argument's own cells with `rplacd`, allocates nothing, so its
  head cell ends up mid-result.
- interpreter: builds a fresh list, argument untouched.

Return value is identical on all four; use it.

## Injection gate and fallback

- `LispMacroExpander.programUsesSort` -- program or generated wrapper body names `sort` or
  `stable-sort`. The `#'sort` wrapper does not exist until the backend generates it, which
  is why injection is the BACKEND's, not `expandTopLevelDefinitions`'.
- No array gate (unlike `%subseq-runtime`, `replace`/`fill`): the body is
  `car`/`cdr`/`rplacd` + `funcall`, pulling no runtime in.
- `JvmSortCompiler`/`WasmSortCompiler` (the old inline SELECTION sorts) remain, reachable
  only by a program that DEFINES `%sort-runtime` itself; it gets a correct, quadratic,
  differently-permuting `sort`. Deleting the fallback needs a name a program cannot define.

## Trap: the wasm name registry is armed at the SITE

A symbol designator (`(sort l (car (list 'lt)))`) resolves only through the wasm name
registry, armed by the site that dispatches an unreadable designator
(`Ctx.runtimeDesignatorDispatch`). Injected runtime bodies are excluded
(`Ctx.injectedRuntimeBody`), so `WasmExprCompiler`'s `SORT` case arms it itself, as
`WasmDesignatorCall.prepare` did. Without that the program traps on `unreachable` in the
arity-2 dispatcher.

## Pinning tests

- ci-spec `sort-is-linearithmic-and-stable` (all four; 20,000 elements + repeated keys).
- `LispEvaluatorTest.evalSortOfALargeListIsLinearithmicAndOrdersEqualElementsLikeEveryOtherBackend`
  (100,000, preemptive 30 s timeout).
- `JvmLispCompilerTest.compileAndRunSortOfALargeListIsLinearithmicAndOrdersEqualElementsLikeEveryOtherBackend`,
  `WasmLispCompilerIntegrationTest.sortOfALargeListIsLinearithmicAndOrdersEqualElementsLikeEveryOtherBackend`
  (50,000).
- `LispMacroExpanderTest.theSortRuntimeIsOneMergeSortEveryCompiledSortSiteCalls`.
