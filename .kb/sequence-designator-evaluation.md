# When a `:test` / `:test-not` / `:key` designator form is evaluated

**Invariant: a designator form spelled in a sequence or alist call is evaluated ONCE,
before the scan, in the order the CALL spells its arguments (CLHS 3.1.2.1.2.3) -- unless
it is a LITERAL, which stays inlined in the loop body because evaluating it is not
observable and the compilers' function-designator normalization has to see it there.**

One place decides it for every scan: `LispMacroExpander.KeywordTail`. `of(parts, start,
prefix)` reads a call's keyword tail, hoists what must not stay inlined, and answers a
REWRITTEN `parts` (each hoisted form replaced by its variable) plus `wrap(body)`, the
`let` chain to put around the expansion. An expander's whole share is three lines: build
the tail, take its `parts`, wrap its result.

## What is a literal, and why the distinction is not cosmetic

Inlined: a self-evaluating atom, `(quote x)`, `(function x)`, a `lambda`, and the symbols
`t`/`nil`/a keyword. Hoisted: everything else, a BARE SYMBOL INCLUDED -- it can be a symbol
macro (`define-symbol-macro`), and CL evaluates the value form once whatever it is.

A literal has to stay where it is: `#'name` in the loop body is what
`normalizeFunctionDesignator` and the two compilers turn into a direct call. A computed
form in the same position is wrong twice over -- once per ELEMENT instead of once, and
inside the loop instead of in argument order. A call whose keyword values are all literal
therefore hoists NOTHING (not even a positional argument) and expands byte-identically to
the way it did before, so no existing call site pays.

## What binds, and in what order

`wrap` emits, outermost first: the positional arguments (only the non-literal ones), then
one binding per hoisted keyword value, in SOURCE order. A keyword spelled twice binds BOTH
values and uses the first (ANSI's `member-if.order.2`). The positional arguments have to
come along: the scans bind them INSIDE a sequence dispatch or a `do` loop, which the
keyword values would otherwise have run before.

Hoisting also DEFAULTS a designator, because the expansion can no longer read the literal
that used to tell it so -- ANSI passes a computed nil for `:key` (`subsetp.order.2`):

- `:key` binds as `(or form #'identity)`, `:test` as `(or form #'eql)`. The `or` short-
  circuits, so a non-nil designator allocates nothing and the loop body is unchanged.
- a `:test-not` that the scan will USE (no `:test` beside it) binds as the COMPLEMENTED
  test and is rewritten into the `:test` slot, so the match form is the same `funcall`
  either way. The negation is spelled out as a two-argument lambda
  (`LispMacroExpander.twoArgumentComplement`) rather than delegated to `complement`,
  which could serve it but dispatches four arities behind supplied-p flags -- see below.
- The injected `#'identity` / `#'eql` need no `#'identity` in the SOURCE: the wrapper
  reference gate runs over the EXPANDED tree, so `BuiltinFunctionWrappers` emits them.

## Who is covered

The scans that carry the `:test`/`:test-not`/`:key` set: `expandMember`, `expandAssoc`,
`expandRassoc`, the three `-if` spellings (`expandMemberIf`/`expandAssocIf`/
`expandRassocIf`, which take `:key` ALONE -- the predicate is the test),
`buildPositionScan` (the `position`/`find` six),
`expandCount`/`expandCountIf`, the `remove`/`delete`/`substitute`/`nsubstitute` fifteen
(over `SeqScanScaffold` -- `.kb/sequence-bounding-keywords.md`),
`expandRemoveDuplicates`, and the set operations
`expandAdjoin`/`expandUnion`/`expandIntersection`/`expandSetDifference`/`expandSubsetp`.
**`subst`/`nsubst`/`sublis`/`nsublis`/`merge` were never in this list, and measurement
(2026-09-12) shows they needed nothing:** all five are ordinary prelude `defun`s
(`LispPreludeLibrary`), not expansions -- an ordinary function call already evaluates
every argument, keyword values included, exactly once, in the order the call spells them
(`LispEvaluator.evalArgs`, and the same shape on both compile paths via
`LambdaLists.desugarProgram`/`toNative`), which is CLHS 3.1.2.1.2.3 by construction. A
prior version of this file guessed they needed the same `KeywordTail` treatment as the
scans above and filed that guess as a todo; the ANSI order tests it named
(`subst.order.2`, `nsubst.order.1/2`, `sublis.order.1/2`, `nsublis.order.1/2`) already
passed before any code changed, and `merge.order.1` (the only order test the suite has for
`merge`, which takes no designator besides `:key`) does too. Verified directly against the
interpreter, not inferred from the reason census: each order test's own body, run as a
plain program, prints exactly ANSI's expected values and evaluation-count tuple.

`sort`/`stable-sort` and `search`/`reduce` were the real gap, fixed 2026-09-12:

- **`sort`/`stable-sort`**: `expandStableSort` (the expansion a `sort` call with keywords
  routes through, `expandSortWithKey`) bound the predicate OUTERMOST and left the sequence
  argument inlined at the innermost dispatch position, so a call's own order (sequence,
  predicate, `:key`) came out as (predicate, `:key`, sequence) regardless of keywords --
  ANSI's `sort.order.2`, `stable-sort.order.1/2`. Fixed by moving the predicate/`:key`/
  scratch bindings from wrapping the whole `seqResultDispatchForm` call to wrapping the
  BODY `seqResultDispatchForm` hands its `algo` callback -- the dispatch's own sequence
  binding is then outermost, and the call's positional-then-keyword order falls out
  without touching `seqResultDispatchForm` itself.
- **`search`**: the prelude `defun`'s lambda list took no `:test-not` parameter at all, so
  spelling one signalled `Unknown keyword argument: :TEST-NOT` (`search.order.2`,
  `search-list.16`, and the same gap in `search-vector`/`search-string`/
  `search-bitvector`). Fixed by adding the parameter and branching on it inline (`:test`
  wins when both are spelled, matching every other scan's precedence here); still an
  ordinary function call, so the order came free once the parameter existed.
- **`reduce`**: `expandReduce` decided `:from-end`'s truth in JAVA from the unevaluated
  keyword form (`fromEndForm != null && !isNilForm(fromEndForm)`) and never spliced a
  non-literal form's side effect into the generated code at all -- a computed
  `:from-end` simply never ran (ANSI's `reduce.order.2/3`: the evaluation count came up
  one short and the `:from-end` variable stayed at its `let`-initial value). Every other
  keyword value was also read from the unevaluated call and re-assembled into a NEW
  structural shape (`(mapcar key (subseq seq start end))` wrapped in `reduce` or, under
  `:from-end`, `(reduce swapped (reverse ...))`), so its position in THAT shape decided
  evaluation order, not the call's own spelling -- with `:key`/`:start`/`:end`/
  `:initial-value` free to be spelled in any order (ANSI's `.2` and `.3` swap them
  against each other), no fixed structural shape can match both. Fixed the same way as
  `sort`/`stable-sort`: `KeywordTail.of(parts, 3, "__reduce")` hoists `fn`/`seq` and every
  non-literal keyword value (`:from-end` included) into one source-ordered `let`, so the
  downstream reassembly only ever references already-evaluated variables; `:from-end`'s
  boolean is still folded away in Java when its form is a literal `t`/`nil` (byte-
  identical to before), and only a genuinely computed one costs a runtime
  `(if from-end backward forward)` branch over both fold shapes -- the
  `remove-duplicates` computed-`:from-end` precedent just above. A hoisted (therefore
  possibly-nil-at-runtime) `:key` is defaulted to `#'identity` the way every other
  designator here is; a literal one, nil included, is decided statically as before, no
  wrapper paid.

Each of the three fixes is `KeywordTail.of(parts, start, prefix)` plus `tail.parts()` plus
`tail.wrap(...)`, same as every entry above, EXCEPT that `sort`/`stable-sort`'s existing
structural bug (predicate bound outside the dispatch that reads the sequence) and
`reduce`'s runtime-vs-Java `:from-end` decision needed their own surgery beyond the three
lines -- what to check for the next operator is exactly this: whether its own expansion's
bindings already sit in call order before applying `KeywordTail`, and whether any of its
keywords decides between two STRUCTURALLY DIFFERENT expansions (a runtime branch) rather
than just a value used once.

Pinning: ci-spec `reduce-sort-search-order` (interpreter, JVM, both WASM backends, and the
native-image E2E, `--simd` included) -- computed `stable-sort` sequence/predicate/`:key`,
a computed `reduce` `:from-end`/`:initial-value`/`:start`/`:end`/`:key` together, and
`search` with a computed `:test-not`.

Two argument-order defects in the set operations were the same bug without a keyword and
are fixed beside it: `union` evaluated list-b before list-a (its `do` bound the cursor
first), and `subsetp` inlined list2 into the inner `member` call, so it was evaluated once
per element of list1.

**The interpreter is not one surface here.** `member`/`assoc` are RUNTIME functions in the
interpreter (`LispEvaluator`), which evaluate their arguments before the call and were
already in order -- but read a nil `:key` VALUE as a function to call until
`presentKeyword` replaced `optionalKeywordArg` there. Everything else in the list above
reaches the expansion through `builtinMacroExpansion` (an arm of `evalCons` or
`rareOperatorExpansion`, whose expansion replaces the form in the evaluator's loop), so the
interpreter and the three compile paths run the same hoist.

## What it moved (ANSI, interpreter, 2026-09-11)

`ansi-test/measure.sh sequences cons`, suite `ca06bd9`:

| chapter | before | after |
|---|---|---|
| sequences | 2,861 / 3,287 (87.0%) | 2,891 / 3,287 (88.0%) |
| cons | 1,057 / 1,879 (56.3%) | 1,082 / 1,879 (57.6%) |

A name-by-name diff of the FAIL/ERROR sets: **55 tests fixed, zero tests that passed
before failing after.** The `*.ORDER.*` tests of `count`, `remove`, `delete`,
`substitute`/`nsubstitute` (six spellings), `position`/`find` (six), `rassoc`,
`adjoin`, `union`, `intersection`, `set-difference` and `subsetp` all answer ANSI's
counters exactly now.

What the order tests still failed on, each a different gap -- all three now closed:

A gap stood in that list until 2026-09-12: `member-if.order.2`, `assoc-if*.order.*` and
`rassoc-if*.order.*` want a `:key`, which the `-if` spellings of `member`/`assoc`/`rassoc`
did not take at all -- nor did their `-if-not` complements exist. All six take it now, on
both surfaces (`.kb/cons-set-and-tree-operators.md`).

A second gap stood in that list until 2026-09-11: `remove.order.2` / `delete.order.2` /
`adjoin.order.2` pass `(complement #'eq)` as a two-argument `:test-not`, and `complement`
answered a ONE-argument lambda. Fixed -- see the next section.

A third gap stood in that list until 2026-09-11:
`remove-duplicates.order.1/2` and `delete-duplicates.order.1/2` want
`:start`/`:end`/`:test-not` and a COMPUTED `:from-end`, which `expandRemoveDuplicates`
rejected. Fixed (4 more tests, sequences 2,891 -> 2,895) --
`.kb/sequence-bounding-keywords.md`, "the window bounds what is CONSIDERED".

### `sort`/`stable-sort`/`search`/`reduce` (2026-09-12)

`ansi-test/measure.sh sequences cons` (interpreter, suite `ca06bd9`): sequences 2,950 ->
2,964 / 3,287 (89.7% -> 90.2%), errors 215 -> 206, fails 122 -> 117; cons unchanged
(1,630 / 1,879) -- `subst`/`nsubst`/`sublis`/`nsublis` needed no code change, see "Who is
covered" above. A name-by-name diff: **14 tests fixed, zero regressed** --
`sort.order.2`, `stable-sort.order.1/2`, `reduce.order.2/3` (5 fails), and
`search.order.2`/`search-list.16` plus the same `:test-not`-shaped fix reaching
`search-vector`/`search-string`/`search-bitvector`'s own order/keyword tests (7 errors --
the `Unknown keyword argument: :X` census fell from 30 to 21, exactly this count, with
`mismatch`'s own missing `:test-not` -- out of scope here -- still red).

## What a variadic complement costs (2026-09-11)

**`complement` answers a lambda covering arities 0-3, dispatched with `&optional`
supplied-p flags. Three is where the set stops because the only UNBOUNDED lowering is
`(lambda (&rest args) (not (apply f args)))`, and `apply` is a gate, not an operator.**

The gate is real and was re-measured, not assumed: `(print (+ 1 2))` compiles to 3,955 B
(JVM) / 489 B (WASM); `(print (apply #'+ (list 1 2)))` to 41,041 B / 18,741 B. On the JVM
`apply` forces `usesEval`; on WASM it opens the apply tier (`.kb/eval-runtime.md`).

What the gate does NOT cost is what the old javadoc assumed. Measured on the minimal
program `(let ((f #'evenp)) (let ((g <lambda>)) (print (funcall g 3))))`, where `#'evenp`
has already opened the designator gate the way every `complement` call site does:

| lowering of the complement lambda | JVM | WASM |
|---|---|---|
| one argument (what it used to emit) | 41,840 | 20,988 |
| `(&rest args)` + `apply` (CL-conformant) | 42,045 | 29,301 |
| `(a &rest more)`, arities 1-2 | 42,098 | 23,756 |
| `&optional` supplied-p, arities 0-3 (**landed**) | 43,115 | 25,026 |
| `(&rest args)` + `cond`, arities 0-3 | 43,180 | 25,037 |

So on the JVM the conformant `apply` lowering is the SMALLEST of the four candidates
(+205 B, 0.5%) -- the premise "`apply` drags the whole eval runtime into every compiled
program that uses it" does not survive contact with a program that spells `complement`,
because such a program has already paid. WASM is where it holds: +8,313 B (+40%) against
+4,038 B for the bounded dispatch. Two things decided it for the bounded shape anyway:

- the wrappers. `BuiltinFunctionWrappers.sequenceScanFamily`/`positionFamily` build the
  first-class `#'remove`/`#'position` `:test` on a complemented `:test-not`, and those
  bodies are injected on a reference, not on a call. An `apply` inside them is invisible
  to `needsApplyRuntime` (`APPLY_USING_FUNCTIONS` does not list `remove`), so making
  `complement` variadic would have meant widening that set -- every program naming
  `#'remove` pulling the apply tier, for a keyword it may never pass.
- `&rest` conses. A complemented `:test-not` runs once per ELEMENT of the scan.

Both wrapper sites and `KeywordTail.complemented` know their arity is TWO, so all three
spell it: `LispMacroExpander.twoArgumentComplement`. That is why a program that never
writes `complement` did not grow with this change (41,840 / 20,988 -> 42,308 / 20,847),
while one that does pays for the dispatch (41,840 / 20,984 -> 43,572 / 25,027).

Landing it uncovered one more thing: a nested lambda whose `&optional` carries a default
(`(defun f (n) (lambda (&optional (x 1 p)) ...))`) was a ClassCastException at compile
time -- `compiler/FreeVarAnalyzer.extractParamNames` read only the bare-symbol parameter
shape. The ci-spec case below covers it.

## The `&rest` arm landed (2026-09-23, `.todo/934`)

The 0-3 `funcall` arms stay; a fourth argument takes `&rest more` through
`(apply fn a0 a1 a2 more)`. `twoArgumentComplement`'s known-arity sites are untouched,
so a program that never spells `complement` is byte-identical.

- **The arm alone opens the gate, as feared.** `needsApplyRuntime` scans the
  unexpanded program and misses it, but the gate is a prediction with a self-check:
  the emitted `invokestatic _apply` fails the post-compile `unresolvedSelfMethods`
  check and the build retries with the apply group forced
  (`JvmLispCompiler.compile`, "The gate is a consequence, not a prediction" is the
  array version of the same loop). Verified directly: `needsApplyRuntime` answers
  false on `(print (funcall (complement #'evenp) 3))` and true on its expansion, and
  the compiled class carries `_apply`.
- **Measured** (same minimal program, raw backend harness): JVM `.class`
  19,843 -> 25,796 B (+5,953, +30%); WASM 10,007 -> 12,009 B (+2,002, +20%). A
  program that never spells `complement` is unchanged (the `#'remove` /
  `#'position` `:test-not` wrappers ride `twoArgumentComplement`, and neither the
  examples, the size-report corpus, the shipped libraries nor the e2e sources spell
  it).
- Landed anyway: the cost hits only explicit spellings (rare), while the alternative
  is a permanent program-error on legal calls and two ANSI ERRORs. A subtlety the
  measurement surfaced: on the compiled backends an n-ary spelling of a binary
  built-in (`<`, `char=`) expands pairwise at the call site, which `apply` bypasses,
  so `(funcall (complement #'<) 1 2 3 4)` still fails there with the callee's arity
  error -- the callee's own gap, not this one's. The unit tests pin the arm with
  true `&rest` defuns for that reason.
- **ANSI** (`data-and-control-flow`, interpreter, suite `ca06bd9`, names diffed):
  `COMPLEMENT.4`/`COMPLEMENT.8` ERROR -> PASS, zero regressed (`COMPLEMENT.3` stays
  ERROR on the unbound `*UNIVERSE*`, a harness gap).

## Pinning tests

- `LispMacroExpanderTest.aComputedSequenceDesignatorBindsOnceBeforeTheScan` -- the literal
  stays inlined, the computed one binds once in source order, the `:test-not` shape.
- `LispMacroExpanderTest.aBoundedSequenceScanEmitsOnlyTheScaffoldingItsKeywordsAskFor` --
  the byte-identical expansion a literal call keeps.
- `LispEvaluatorTest.evalSequenceScansEvaluateAComputedDesignatorOnce` -- the ANSI order
  counters, the duplicate keyword, the computed nil designator, call position and
  first-class.
- ci-spec `sequence-designator-order` -- the same counters on all four backends.
- `LispMacroExpanderTest.complementAnswersALambdaThatCoversEveryDesignatorArity` -- the
  arity arms, the once-evaluated function form, the `apply` confined to the `&rest`
  arm (exactly one occurrence), and the
  two-argument shape the known-arity sites spell instead.
- `LispEvaluatorTest.complementServesEveryDesignatorArityItsCallersUse` and ci-spec
  `complement-designator-arity` -- the behaviour, in the interpreter and on all four
  backends, including the first-class `(apply #'remove ... :test-not ...)` path,
  the defaulted-`&optional` nested lambda, and the 4+-argument `&rest` arm over a
  true `&rest` defun -- with
  `JvmLispCompilerTest#compileAndRunComplementAppliesBeyondThreeArguments` and
  `WasmLispCompilerIntegrationTest#complementAppliesBeyondThreeArguments`.
