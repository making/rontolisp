# `defmacro` (user macros) + read-time backquote — NO backend codegen involved

Backquote (`` ` ``/`,`/`,@`; `Token.Backquote`/`Unquote`/`UnquoteSplicing`, `,` and `` ` `` are symbol-terminating chars, digit-grouping `1,000` still lexes inside `readNumber`) is expanded BY THE READER (`LispReader.readBackquote`/`readTemplateElement`) into plain `list`/`append`/`cons`/`quote` forms, so all backends get it for free. `',@xs` / `#',@xs` (a splice directly after `'`/`#'`, `readWrappedTemplate`) is the template `(quote ,@xs)` / `(function ,@xs)`, lowered to `(cons 'quote xs)` — the customary one-element splice reads back as `'x` (trivia level0's `` `(equal ,*what* ',@args) ``, same idiom in type-i); empty and multi-element splices yield `(QUOTE)` / `(QUOTE A B)`, matching SBCL's list structure with no arity special-casing. Tests: `LispReaderTest#readBackquoteSplicingIntoQuote`, `LispEvaluatorTest#evalBackquoteSplicingIntoQuote`, ci-spec `backquote-quoted-splice`. A splice COMBINED with a dotted tail is legal (todo-243): `` `(x1 ... xn . tail) `` with splices is `(append [x1] ... [xn] tail)` per CLHS 2.4.6.1 — the tail (an unquote, or a constant that `readTemplateElement` quotes) becomes the LAST append argument (`buildTemplateList`; trivia level2's `` `((,head ,@(mappend #'car pairs) . ,(cdr (last args)))) ``). `,@` directly after the dot stays an error. Tests: `LispReaderTest#readBackquoteSplicingWithDotted{UnquoteTail,ConstantTail,TailTriviaShape}`, ci-spec `trivia-enablement-language-group`.

**`,.` is `,@`** (added for iterate, 2026-08-27): CLHS 2.4.6 gives comma-dot the semantics of comma-at plus PERMISSION to destroy the spliced list, and splicing non-destructively is conformant -- so `LispLexer` emits one `Token.UnquoteSplicing` for both spellings and nothing downstream distinguishes them. Without it `,.init-code` read as an unquote of a symbol named `.init-code` and `,.(if ...)` was the read error `Unexpected '.'` (iterate.lisp:598, the whole of `expand-iterate` is written in this spelling). Tests: `LispReaderTest.readBackquoteCommaDot*`, `LispEvaluatorTest.evalCommaDotSplicesLikeCommaAt`, ci-spec `sharp-l-comma-dot-and-hash-table-iterator`.

**Nested backquote** is supported. A non-nested template keeps the optimized single-level expander (`readTemplateElement`/`readTemplateList`, unchanged output shapes). `readBackquote` first raw-reads the template (`readRawTemplate`) to detect an inner `` ` ``; if present, the whole template goes through a faithful port of the CLtL2/Steele Appendix C algorithm (`bqCompletelyProcess` = `bqProcess` + `bqSimplify` + `bqRemoveTokens`, over identity-compared sentinel markers `BQ_COMMA`/`BQ_BACKQUOTE`/`BQ_LIST`/...). Every quasiquote level is expanded at read time into `list`/`cons`/`list*`/`append`/`quote` calls with NO runtime quasiquote/comma marker left, so the evaluator and all four backends are unaffected. The one deviation from CLtL2: an inner backquote that escapes to level 0 (inside a comma argument) is expanded in place by `bqExpandEscaped` rather than left as a live `backquote` macro call, because the runtime has no backquote. Verified against SBCL (incl. `cl-utilities` `once-only`, three levels deep). Tests: `LispReaderTest` (`readNestedBackquote*`), `LispEvaluatorTest#defmacroNestedBackquoteOnceOnly`, `JvmLispCompilerTest#compileAndRunNestedBackquoteOnceOnly`, `WasmLispCompilerIntegrationTest#nestedBackquoteOnceOnly`, ci-spec `nested-backquote-once-only`.

**Template symbols are package-resolved**: inside a `defmacro` body or a `macrolet` definition list, `PackageResolver` resolves quoted data too (the backquote expansion above turns template symbols into quoted symbols before the resolver runs), so a bare template symbol resolves against the DEFINING package — cl-utilities' `(zero-length-p ,seq)` becomes `cl-utilities::zero-length-p`, matching the defun. On the compile path `UserMacroExpander` resolves every form through its macro evaluator's resolver so qualified call sites match the registered canonical macro names. Details: `.kb/packages.md`.

`defmacro` (`CL_SPECIAL_FORMS`; cannot redefine a cl symbol; no function value) is handled per path:

- The **interpreter** keeps a macro table on `LispEvaluator` (`userMacros`, `expandUserMacro` binds the UNevaluated arg forms and evals the body; checked in `evalCons` after the built-in switch, so it also works in REPL/`load`/runtime `eval`).
- The **compile path** runs `eval.UserMacroExpander.expand` in `RontoLispCli.compileToFile` (after `LoadInliner`, before the compilers — same pattern), which evals `defmacro` forms into a macro-time `LispEvaluator`, registers top-level `defun`s (registration only, so macro bodies can call helpers), fully expands every call site with a structure-aware walker (skips `quote`, `let`/`do` binding names, `lambda`/`defun` params, `case`-family keys, `dolist`/`dotimes` vars) and drops the definitions — the compilers never see a macro form, so there is NO `Jvm/Wasm` macro compiler and nothing to keep in sync. Anything compiling programs without the CLI (corpus tests) must apply the pass itself.
- The variable half of that registration is **LAZY**, and deliberately so. `defun`/`defclass`/`defgeneric`/`defmethod`/`define-condition`/`defstruct` are registered eagerly (bodies do not run), but `defvar`/`defparameter`/`defconstant` go through `LispEvaluator.registerLazyGlobal`: the name is proclaimed special immediately while the value expression is parked as a thunk in the macro-time global environment (`Environment.defineLazy`; `lookup`/`lookupOrNull` force it, `isBound` counts it as bound, `define`/`set` discard it, and the non-forcing `hasBinding` serves the existence probes in `boundp`/`find-symbol`). It runs only if something READS that global while macros expand. **Why**: a quickloaded library that builds tables in a top-level `defvar` otherwise builds them twice — once in the macro-time interpreter and once again in the compiled program — and the interpreter run dominates the compile. `(ql:quickload "uax-15")` to a `.class` measured 86.5 s eager against 3.6 s lazy, byte-identical output. Forcing is reached from BOTH the macro-body read path and the `#.` marker channel (`.kb/reader-features.md`), which is why it lives in `Environment` and not at the expansion entry point. A value expression that fails to evaluate is reported and leaves the name unbound, exactly as the eager `try`/warn did — the warning just moves to the first read, and a never-read broken init no longer warns at all. Two consequences of the moved capture point, both deliberate: the expression sees the macro-time evaluator as of the FIRST READ, so one calling a `defun` defined later in the program now succeeds where it used to warn; and because the resolver's `in-package` state has moved on by then, the thunk captures the current package at the DEFINITION and restores it while running (`intern` homes there, `.kb/symbol-runtime-api.md`). **Do not confuse this with uax-15's lazy TABLES** (`.kb/asdf.md`): that is a RUN-time deferral, expressed in the emitted Lisp itself (`(or *T* (%lite-build-T))`) so it holds on all four backends, where this one is a MACRO-time deferral inside the compile path's interpreter and only reaches globals the expander reads. uax-15 is no longer an example of the paragraph above either — its tables are published by a `defun`, not by a top-level `defvar`, so `registerLazyGlobal` never sees them.

Consequences: the runtime `_eval`/`read` of compiled output knows neither `defmacro` nor `` ` ``; macros must be defined before use.

## A call site is expanded ONCE, on every backend (2026-08-25)

**Invariant: a user-macro call form is expanded once per source occurrence, not once per
evaluation — the interpreter included.** The compile path always worked this way
(`UserMacroExpander` walks the program and replaces each call site with its expansion);
the interpreter used to call `expandUserMacro` from `evalCons` on every visit, so a macro
call inside a function body re-interpreted the whole macro body on every call of that
function. `LispEvaluator.userMacroExpansions` — an `IdentityHashMap` keyed on the call
site's cons, bounded by `EXPANSION_MEMO_LIMIT`, the same shape as the
`compilerMacroExpansions`/`loadTimeValues` memos next to it — closes the gap.

The cost it removes is not marginal, because a macro body is an ordinary interpreted
program and a serious one is a compiler. `trivia`'s `match` compiles its patterns at
expansion time, ~100 ms a call:

| `(fib 10)` written with `trivia:match`, interpreter | before | after |
| --- | --- | --- |
| 177 `match` expansions | 17,385 ms | **115 ms** |

The two memos below it also start hitting for free: a re-expansion handed them a FRESHLY
consed call form every time, so a `define-compiler-macro` rewrite and the
`load-time-value` slot inside it were rebuilt per iteration
([compiler-macros.md](compiler-macros.md)).

**Invalidation.** A cached expansion is only valid for the macro definitions that
produced it, so every write to the `userMacros` table goes through
`LispEvaluator.putUserMacro`/`removeUserMacro`, which drop the WHOLE memo: a redefined
`defmacro`, `fmakunbound`, `(setf (symbol-function ...))`, a `(setf (macro-function ...))`
alias, and `macrolet`/`pushLocalMacro` entering or leaving scope (a `macrolet` replaces the
table for a dynamic extent, so the same call site genuinely means something else inside
it). Dropping everything rather than the affected call sites is deliberate: the memo
refills as the sites are reached again, and there is no edge to get wrong.

The memo is guarded by its own monitor, never held across an expansion (a macro body is a
whole program, re-enters the expander, and may take the library load lock): a macro call is
ordinary Lisp, so it is reachable from a served request, and that is one virtual thread per
request ([concurrent-served-requests.md](concurrent-served-requests.md)). Two threads
racing on one call site both expand and the last write wins — a wasted expansion, not a
wrong answer, because each expansion is self-consistent.

**What it changes semantically**: a macro body that READS state while expanding (cl-who's
`with-html-output` consults `*html-mode*`) now freezes the first answer. That is what a
compiled program has always done, so this moves the interpreter TOWARD cross-backend
identity, not away from it. The interpreter-only gensym counter caveat in
[gensym-macroexpand.md](gensym-macroexpand.md) shrinks the same way: a macro body calling
`gensym` bumps the counter once per call site now, not once per evaluation.

Built-in macros (`loop`, `dolist`, `cond`, `setf`, ...) are still re-expanded per
evaluation — `evalCons` lowers them through `LispMacroExpander.expand*` inline. Those
expansions are pure functions of the form, so memoizing them would need no invalidation at
all, but the memo would be far hotter than this one; measure before assuming the lookup
pays for itself.

Tests: `LispEvaluatorTest#userMacroExpandsOncePerCallSite` (a counter in the macro body:
three calls through one call site expand once, a second call site expands again),
`#redefiningAMacroReexpandsItsCallSites`, `#macroletEnteringAndLeavingScopeInvalidatesTheExpansionMemo`.

## `destructuring-bind` + macro lambda lists (shared destructuring machinery)

`destructuring-bind` is a `LispMacroExpander` lowering (`expandDestructuringBind`, `CL_MACROS`, wired into the evaluator + all three compilers + `FreeVarAnalyzer` + `rewriteLocalCalls`/`UserMacroExpander.expandAll` pattern-keeping cases): the pattern becomes a `let*` of car/cdr chains over a `__db<N>_whole` temp. A keyword-free pattern reuses the plain pairs walker shared with `loop` destructuring (`destructurePairs`, lifted out of `LoopExpander`); a pattern with lambda-list keywords binds the required prefix positionally, then `LambdaLists.appendTailBindings` (a flat-binding variant of the `LambdaLists.expand` prologue, incl. the unknown-`&key` check as a `__ll_check` throwaway binding) handles `&optional`/`&rest`/`&body`/`&key`/`&aux` over a `__db<N>_r<i>` rest temp. Nested sub-patterns recurse (keyword-using ones through their own `__db<N>_g<i>` temp). A dotted tail in a keyword-USING pattern is normalized to `&rest` (`destructuringBindings`; trivia level0 destructures clauses as `((pattern &rest body) . rest)`) — the keyword-free path already handled dotted tails in `destructurePairs`. Lite semantics: NO mismatch errors (missing → nil, surplus ignored). **`&whole` works** in BOTH forms (added with the cl-postgres/alexandria enablement, `.kb/asdf.md`): as the pattern's first element it binds its variable to the whole source list and the remaining pattern destructures the same source again (`destructuringBindings`, safe because the accessor chain is side-effect-free), and in a `defmacro` lambda list `LispEvaluator.evalDefmacro` binds it to the rebuilt call form `(cons 'name args)` and forces the destructuring path so the internal rest variable exists. `&environment` in a MACRO lambda list is stripped and bound to nil by `makeUserMacro` before the pattern reaches the destructuring machinery -- so a portable macro that merely PASSES it on (cl-ppcre hands it to `get-setf-expansion`) works, one that expects a real environment object does not -- and it is still an error inside `destructuring-bind` itself.

`defmacro` lambda lists beyond "required + one `&rest`/`&body`" route through the same machinery: `LispEvaluator.evalDefmacro` detects a non-simple lambda list (`isSimpleMacroLambdaList`) and stores the macro as rest-only (`__macro_args`) with the body wrapped in `(destructuring-bind <lambda-list> __macro_args body...)`, validated eagerly by a dry-run expansion (definition-time errors). Both consumers agree for free because `UserMacroExpander`'s macro-time evaluator IS a `LispEvaluator`. Simple lambda lists keep the old path (strict arity check + its error message); extended ones inherit the lite no-mismatch semantics.

Tests: `LispReaderTest`/`LispLexerTest` (backquote), `LispEvaluatorTest` (defmacro + destructuring-bind sections), `UserMacroExpanderTest`, `JvmLispCompilerTest#compileAndRunUserMacroAfterExpansionPass`/`#compileAndRunDestructuringBind`/`#compileAndRunUserMacroWithDestructuringLambdaList`, `WasmLispCompilerIntegrationTest#destructuringBindForms`, ci-spec `defmacro-user-macros`/`destructuring-bind-and-defmacro-lambda-lists`. Follow-ups in `.todo/044`.

## `(setf (macro-function 'new) (macro-function 'existing))` — macro aliases (todo-242, 2026-08-02)

**Invariant: a macro alias is a write to the MACRO TABLE, carried out by whichever pass
owns that table, and the form never reaches a backend.** A plain `(macro-function 'name)`
is a real call answering a real expander since todo-378 (`.kb/symbol-runtime-api.md`), but
the alias never evaluates one: it is recognized SYNTACTICALLY, before the value form would
run —
`LispMacroExpander.isSetfMacroFunctionForm` + `macroFunctionArgumentName` (both public,
shared) match `(setf (macro-function 'new) (macro-function 'existing))`. The interpreter
handles it in `evalCons`'s `SETF` case (`LispEvaluator.aliasMacroFunction`, before the
place expansion) by putting the existing `UserMacro` under the new name, so the two names
share ONE expander from then on; the compile path handles it in `UserMacroExpander.expand`
by replaying the form into the macro-time evaluator and DROPPING it, exactly like the
`defmacro` it aliases (the same predicate is in the pass's activation list, so a program
whose only macro business is an alias still activates it, and `expandAll`'s `SETF` case
returns the shape verbatim so the walk cannot turn either half into an ordinary call).
Both sides therefore agree by construction — the compile path's macro table IS a
`LispEvaluator`, and the alias name is in the table `macro-function` answers from.

Reaching `expandSetf`'s `MACRO-FUNCTION` case means neither interception applied, i.e. the
form is not an alias of a user macro; it throws naming the one supported shape. The
consumer is lisp-namespace's `(setf (macro-function 'nslet) (macro-function 'namespace-let))`
(a trivia.level2 dependency, `.todo/238`). **Deliberate narrowness, with its re-evaluation
trigger**: an arbitrary expander FUNCTION (a lambda over form+env) is rejected because
there is no macro function object to store — the day a program needs one, the macro table
would have to hold callables (`macro-function` answers a `LispFunction` on the
interpreter, but it is built on demand from the table entry, not stored in it); the
lookup is package-tolerant (`LispEvaluator.lookupUserMacro`, exact → member of the
qualified spelling → unique member match) because a QUOTED name is resolved against the
current package while the table may be keyed by either spelling. The alias captures the
expander at alias time, as in CL, where the alias captured the function object: a later
redefinition of either name replaces only that name's entry. Tests:
`LispEvaluatorTest#setfMacroFunctionAliasesAUserMacro`/`#setfMacroFunctionRejectsNonAliasShapes`,
`JvmLispCompilerTest#compileAndRunSetfMacroFunctionAliasAfterExpansionPass`,
`WasmLispCompilerIntegrationTest#compileSetfMacroFunctionAliasAfterExpansionPass`, ci-spec
`defmacro-user-macros`. The class-name twin of this idiom is `(setf (find-class ...))`,
`.kb/clos.md`.

A `define-compiler-macro` reuses every mechanism above (`makeUserMacro`, the
`&whole`/`&environment` handling, `expandMacroCall`) but lives in its own table and
is applied only after a same-named `defmacro` has had its chance — see
`.kb/compiler-macros.md`, which also covers `load-time-value`'s evaluate-once
contract, the pair being what makes a library's own constant-folding optimization run.

## The macro-time evaluator is the "compiling image": spliced-system replay + expanded eval-when (todo-243, 2026-08-03)

In CL, compiling a file requires its DEPENDENCY systems LOADED into the compiling image —
fully executed, registries built. Under the splice model the macro-time evaluator is that
image, and until todo-243 it under-loaded: only definitions registered
(`registerMacroTimeDefinitions`), so a library that builds a macro-consulted registry
with plain top-level CALLS was invisible to expansion. trivia registers its vector
matchers with top-level `(set-vector-matcher ...)` calls and a `dolist`; the pattern
lookup missed, the expander fell back to its structure-pattern guess, and the emitted
code called nonexistent accessors (an NPE three layers later). Two rules retire that:

- **Spliced-system replay** (`UserMacroExpander.replayLibraryTopLevel`): inside the
  `(%begin-system ...)`/`(%end-system)` provenance brackets ([library-defun-pruning.md](library-defun-pruning.md)),
  every plain top-level form is EVALUATED into the macro-time evaluator — progn walked
  member-wise, the defvar family still lazy (`registerLazyGlobal`, the 86.5s→3.6s
  uax-15 lesson), atoms/quotes skipped, failures warned and skipped. USER-file forms
  (depth 0) keep compile-file semantics: definitions register, nothing else runs, so a
  user's own top-level side effect is never double-run. Replay output is discarded (the
  macro evaluator prints to a null stream); the forms stay in the program, so the
  compiled artifact still runs them at run time — the same double execution CL's
  load-then-compile-then-load gives.
- **A macro-EXPANDED `(eval-when ...)` honors its situations**
  (`UserMacroExpander.processExpandedEvalWhen`; source-level top-level eval-whens were
  already flattened before the loop): `:compile-toplevel`/`compile` replays every member
  (lisp-namespace's `define-namespace` and trivia's `defoptimizer` build their
  symbol->function registries there), `:load-toplevel`/`load` keeps the member in the
  program, a defmacro member is consumed, a nested eval-when recurses, and a
  neither-situation member is dropped (CL compile-file semantics).

Both are pinned end-to-end by `TriviaE2eTest` (the JVM/WASM legs die at expansion or at
run time without them).
