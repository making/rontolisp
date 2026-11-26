# The cons set / tree family: the `n`-prefixed spellings, the `-if-not` complements, and their one first-class shape

**Invariant: every `n`-prefixed operator of CLHS chapter 14 except `nbutlast` is an ALIAS
for its non-destructive sibling, and every `-if-not` spelling is the `-if` operator over
the negated predicate. Both are prelude defuns (`LispPreludeLibrary`), so one definition
serves the interpreter and all three compile paths, and the operator it delegates to stays
the only implementation.**

The family: `nunion`, `nintersection`, `nset-difference`, `nset-exclusive-or`, `nsubst`,
`nsubst-if`, `nsubst-if-not`, `nsublis`; `subst-if`, `subst-if-not`; `member-if-not`,
`assoc-if-not`, `rassoc-if-not`; and the four with no sibling to delegate to --
`nbutlast`, `list-length`, `tailp`, `get-properties`.

## Why an alias is not a stub

CLHS states the destructive promise of each `n`-prefixed operator as a PERMISSION: "may
destroy" the argument, never "must". The suite agrees by construction -- its own tests
reach `nunion` / `nintersection` / `nset-difference` / `nset-exclusive-or` through
`nunion-with-copy` / `nset-difference-with-check` (`auxiliary/cons-aux.lsp`), which
`copy-list` both arguments first precisely because an implementation may or may not mutate
them. Nothing in the chapter asserts an `eq` between an argument and the answer.

`nbutlast` is the exception and is really destructive: `nbutlast.1` asserts the answer is
`eq` to the argument AND that the argument's own cells kept their identity, so the prelude
defun counts the conses and cuts the spine with one `rplacd`. Counting first is what makes
a count past the end -- `nbutlast.7` passes `most-positive-fixnum + 1` -- a subtraction
rather than a walk. `butlast`'s own optional count (`LispMacroExpander.expandButlastCounted`)
follows the same shape for the same reason, and the UNCOUNTED spelling keeps its original
one-pass loop byte-identical: the piecewise rule of `.kb/sequence-bounding-keywords.md`
holds here too.

## One tree walk, one match function

The whole `subst` family rides `%subst-walk`, a prelude defun taking the match as a
FUNCTION of the subtree. `subst` hands it the `:test`/`:test-not`/`:key` comparison, the
`-if`/`-if-not` spellings hand it the predicate straight or negated, and the structure
sharing -- an unchanged subtree comes back `eq`, including the spine suffix below the
deepest change -- is decided once, inside the walk. Before 2026-09-12 the sharing logic
was inline in `subst` and there was nothing else in the family to share it with.

`sublis` took its keywords under the names `:%sb-key` / `:%sb-test` / `:%sb-test-not`,
because the defun's parameters carried the prelude's usual `%`-prefix and a `&key`
parameter's NAME is the keyword. So `(sublis alist tree :test #'equal)` answered `Unknown
keyword argument: :TEST` for a keyword its own documentation listed. The parameters that
name keywords are now spelled plainly.

## The first-class surface: three places, none of them a second implementation

A first-class call (`#'union`, `(apply #'set-difference ...)`) is served the way the
sequence-scan family is (`.kb/sequence-bounding-keywords.md`, "The three surfaces"):

- **call position, every backend**: the expansion --
  `LispMacroExpander.expandUnion`/`expandIntersection`/`expandSetDifference`/
  `expandAdjoin`/`expandSubsetp`, and `expandMember`/`expandAssoc`/`expandRassoc` plus the
  three `-if` spellings.
- **interpreter runtime twin**: `LispEvaluator.setOperationValues`, one scan for all five
  set operations, parameterized by `SetOp`. It is registered in `LispEvaluator`, not
  `Environment`, because the `:test`/`:key` designators are applied through the evaluator
  -- which is why the five names are in `ShadowedBuiltins.EXPANSION_LOWERED`.
- **compile paths**: `BuiltinFunctionWrappers.designatorFamily`, the `sequenceScanFamily`
  shape minus CLHS 17.2.1's bounding set -- the wrapper re-extracts the runtime keywords
  with `getf` and calls the CALL-POSITION form, so the expansion stays the only
  implementation. A `:test-not` is normalized to a complemented `:test` through
  `LispMacroExpander.twoArgumentComplement`, so no `apply` reaches the injected body
  (`.kb/sequence-designator-evaluation.md`, "What a variadic complement costs").

Until 2026-09-12 the interpreter's half was an `eql`-only two-argument `LispFunction` in
`Environment` and the compile path's was `binary(...)`, so every
`(apply #'set-difference x y :test f)` answered `SET-DIFFERENCE expects 2 arguments, got
4` -- which is how ANSI calls the entire set chapter.

## `:key` on the `-if` spellings

`member-if` / `assoc-if` / `rassoc-if` took no `:key` at all (`MEMBER-IF expects 2
arguments, got 6`), and their `-if-not` complements cannot be written without one. All six
take it now, through the same two pieces as the rest: `KeywordTail` in the expansion (so a
computed designator binds once, in argument order) and `presentKeyword` in the
interpreter's runtime functions. The keyword tail is `:key` ALONE -- the predicate IS the
test -- validated by `LispMacroExpander.keyKeywordTailError` and
`LispEvaluator.requireKeyKeyword`.

## An odd keyword tail is a `program-error`

`LambdaLists.unknownKeyCheck` walked the tail two cells at a time and never noticed a
tail of ODD length, so `(sublis nil 'a :test)` -- a declared keyword with no value --
bound nothing and returned. It now signals `Odd number of keyword arguments: :TEST`
(CLHS 3.5.1.6; no `:allow-other-keys` makes it legal). The UNKNOWN-indicator complaint
still comes first, because a trailing POSITIONAL argument is both an unknown indicator and
an odd tail and naming it is the more useful reading -- `(linalg:sum m 0)`, the
numpy-style libraries' own trap, still answers `Unknown keyword argument: 0`.

## What it moved (ANSI `cons`, interpreter, 2026-09-12)

`ansi-test/measure.sh cons`, suite `ca06bd9`: **1,108 / 1,879 -> 1,610 / 1,879 pass
(59.0% -> 85.7%)**, errors 626 -> 77. A name-by-name diff of the FAIL/ERROR sets shows
**503 tests fixed and one that passed before failing after** -- `sublis.error.3`, which
had been passing for the wrong reason (`:test` was an unknown keyword there) and is fixed
by the odd-tail rule above, in the same change.

The census in `.todo/740` priced the seventeen missing operators at ~458 ERROR lines. The
realized gain is larger, not smaller, because the census could only count names that were
UNDEFINED: it could not see that `subsetp`, `set-difference`, `sublis`, `union`,
`intersection` and `butlast` -- all six recorded as "already present and correct" -- were
losing another ~90 tests to the two surfaces above (the first-class keyword set, and
`butlast`'s missing count). A census of error TEXT ranks what is absent; it cannot rank
what is present and wrong.

## Pinning tests

- `LispEvaluatorTest.evalConsSetAndTreeOperators` -- the seventeen operators, the
  first-class set family with keywords, and `:key` on the six `-if`/`-if-not` spellings.
- `LispMacroExpanderTest.aCountedButlastEmitsTheLengthPassAndTheUncountedOneDoesNot` --
  the piecewise rule for `butlast`'s optional count.
- `JvmLispCompilerTest.compileAndRunConsSetAndTreeOperators`,
  `WasmLispCompilerIntegrationTest.consSetAndTreeOperators` and ci-spec
  `cons-set-and-tree-operators` (all four backends).
