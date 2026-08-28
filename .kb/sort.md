# `sort` is one merge sort, shared by every backend

**Invariant: `sort` is O(n log n) on the interpreter, the JVM backend and both
wasm backends, and it is the SAME merge sort -- same split, same question to the
predicate -- so the four answer one permutation for one input. No backend
carries a sort of its own algorithm.**

Two homes, one algorithm:

- `LispMacroExpander.sortRuntimeWrapper()` -- the `%sort-runtime` defun, Lisp
  source, injected once per program by `JvmLispCompiler` / `WasmLispCompiler`
  beside the other shared sequence helpers. Every `sort` site on a compile path
  is the call `(%sort-runtime list predicate)`.
- `LispEvaluator.sortValues` -- the same algorithm in Java, over an array of the
  elements, for the interpreter's native `sort`.

## What it replaced, and why the complexity was the bug

The interpreter sorted by INSERTION and both compile paths by SELECTION, each
spelled out where it lived. Both are quadratic. `bench-report/`'s `sort` -- 400,000
integers -- was the one cell in the whole table that timed out on all three
rontolisp backends while SBCL did it in 144 ms. Measured on the JVM backend
before the change: 12,500 elements 917 ms, 25,000 3,470 ms, 50,000 14,534 ms --
doubling n quadrupling the time, so 400,000 was hours.

After, measured by `measure.sh` on the machine that produced the report those
"before" numbers came from: interpreter **1,743 ms**, JVM **430 ms**, wasm
**478 ms**, against SBCL 143, ECL 347 and ABCL 1,416 -- every cell in the row a
number, and every other row within that machine's run-to-run noise. The
CHECKED-IN report still reads `timeout` for the row until the bench-report
workflow is triggered again: `results/` is regenerated on CI so that its diff
is one machine's numbers against the same machine's, which a local run
committed on top of a hosted one would not be.

The comment the insertion sort carried -- that it "keeps the ordering
self-consistent" -- was protecting something a merge sort does not give up: the
sort asks the predicate ONE question, `(pred right left)`, and never assumes a
total order the predicate does not provide. What a merge sort does not preserve
is the exact permutation of EQUAL elements, which is precisely what ANSI
declines to specify for `sort`.

## The algorithm, which is the shared decision

Top-down merge sort over the list:

1. Cut the list in two at the middle cell, found by the two-pointer walk (`fast`
   advancing twice per step), so the LEFT half is the longer one on an odd
   length -- `LispEvaluator.sortValues` splits at `from + (length + 1) / 2`, the
   same boundary.
2. Sort both halves.
3. Merge, asking `(pred right left)` once per step and taking the LEFT element
   unless that answers true.

Step 3 is what makes the sort STABLE, and steps 1-3 together make the output
permutation a function of the predicate's answers alone -- so an inconsistent
predicate (one that is not a strict weak order) still gives the same answer on
every backend, which a differently-shaped merge would not. **Change the split or
the tie rule in one home and you must change the other**; the cross-backend
pin is `ci-spec.yaml`'s `sort-is-linearithmic-and-stable`, which prints the
permutation of a list whose keys repeat.

`sort` being stable is a FACT about the current implementation, not a promise:
`doc/*/reference/functions/sort.md` still says the order of equal elements is
unspecified, and `stable-sort` keeps its own index decoration
(`LispMacroExpander.expandStableSort`) rather than resting on it. So
`sort` and `stable-sort` differ in what they PROMISE about equal elements and in
`:key`, and in nothing else -- least of all in complexity: `stable-sort` sorts
its decorated list by calling `sort`, and inherited O(n log n) with it.

## What the input list looks like afterwards

Undefined in ANSI, and it differs -- as it did before:

- compile paths: the helper relinks the argument's own cells with `rplacd` and
  allocates nothing, so the argument's head cell is somewhere in the middle of
  the result. Use the return value (the doc pages say so).
- interpreter: `sortValues` builds a fresh list and leaves the argument's cells
  alone, as its insertion sort did.

The RETURN VALUE is identical on all four; only this is not, and no test pins
it.

## The injection gate and the fallback

`LispMacroExpander.programUsesSort` -- the program, or a generated wrapper body,
names `sort` or `stable-sort`. `stable-sort` counts because its expansion sorts
the decorated list; the `#'sort` wrapper counts because a first-class `sort` is a
site of its own, and (as with `%subseq-runtime`) it does not exist until the
backend generates it, which is why injection is the BACKEND's and not
`expandTopLevelDefinitions`'.

No array gate on either backend, unlike `%subseq-runtime` and the `replace` /
`fill` pair: the body is `car`/`cdr`/`rplacd` and a `funcall` of its predicate,
so it pulls no runtime in.

`JvmSortCompiler` / `WasmSortCompiler` -- the old inline selection sorts -- are
still there, and reachable only by a program that DEFINES `%sort-runtime`
itself, in which case the backend declines to inject over that name and the site
keeps its inline sort. That program gets a correct, quadratic, and (being a
selection sort) differently-permuting `sort`. If the fallback is ever deleted,
the name has to become one a program cannot define.

## The wasm name-registry arming, which the site must do itself

A symbol designator -- `(sort l (car (list 'lt)))` -- resolves only through the
wasm name registry, and the registry is armed by the SITE that dispatches a
designator the compiler cannot read (`Ctx.runtimeDesignatorDispatch`). The
helper's `funcall` cannot arm it: injected runtime bodies dispatch a designator
parameter in every program ever compiled, so they are deliberately excluded
(`Ctx.injectedRuntimeBody`). `WasmExprCompiler`'s `SORT` case therefore arms it
at the site, exactly as `WasmDesignatorCall.prepare` did for the inline sort.
Without that the program traps on `unreachable` in the arity-2 dispatcher --
which is what it did, on the first build of this change.

## Pinning tests

- `ci-spec.yaml`'s `sort-is-linearithmic-and-stable` -- all four backends: a
  20,000-element sort (out of reach for a quadratic sort) and the permutation of
  a list with repeated keys.
- `LispEvaluatorTest.evalSortOfALargeListIsLinearithmicAndOrdersEqualElementsLikeEveryOtherBackend`
  -- 100,000 elements under a preemptive 30-second timeout, so a quadratic
  regression fails instead of hanging the suite, plus the same permutation.
- `JvmLispCompilerTest.compileAndRunSortOfALargeListIsLinearithmicAndOrdersEqualElementsLikeEveryOtherBackend`
  and `WasmLispCompilerIntegrationTest.sortOfALargeListIsLinearithmicAndOrdersEqualElementsLikeEveryOtherBackend`
  -- the same program compiled, 50,000 elements.
- `LispMacroExpanderTest.theSortRuntimeIsOneMergeSortEveryCompiledSortSiteCalls`
  -- the site routes to the helper exactly when the program carries it, the
  helper is a merge sort (it calls itself and relinks), and the injection gate
  answers for `sort`, for `stable-sort` and for neither.
