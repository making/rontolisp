# `defmacro` (user macros) + read-time backquote — NO backend codegen involved

## Backquote is a READER expansion
`` ` ``/`,`/`,@` (`Token.Backquote`/`Unquote`/`UnquoteSplicing`; `,` and `` ` `` are
symbol-terminating, digit-grouping `1,000` still lexes in `readNumber`) expand in
`LispReader.readBackquote`/`readTemplateElement` into plain `list`/`append`/`cons`/`quote`,
so all backends get it for free.

- `readWrappedTemplate`: `',@xs` is `(cons 'quote xs)`; empty and multi-element splices yield
  `(QUOTE)` / `(QUOTE A B)`, matching SBCL with no arity casing.
- Splice + dotted tail is legal (CLHS 2.4.6.1): the tail becomes the LAST `append` argument
  (`buildTemplateList`). `,@` directly after the dot stays an error.
- `,.` is `,@` -- one `Token.UnquoteSplicing` for both spellings. Without it `,.init-code`
  read as an unquote of `.init-code` and `,.(if ...)` was `Unexpected '.'`.
- `` `#(a ,b) `` is `` `,(apply #'vector `(a ,b)) `` (CLHS 2.4.6.1): `readRawTemplate` builds that when the
  literal holds a comma (`vectorHoldsComma`), and the flag sends it through the full processing, which takes
  any depth. One with no comma stays a literal, reused by every evaluation.
- Nested backquote: `readBackquote` raw-reads first (`readRawTemplate`) to detect an inner
  `` ` ``; non-nested keeps the optimized single-level expander, nested goes through the
  CLtL2/Steele Appendix C port (`bqCompletelyProcess` = `bqProcess` + `bqSimplify` +
  `bqRemoveTokens`, over identity-compared `BQ_*` sentinels). Every level expands at READ
  time. One deviation: an inner backquote escaping to level 0 is expanded in place by
  `bqExpandEscaped`, the runtime having no backquote. A reader macro's datum carrying a raw
  comma marker (its recursive read inside the template, [readtables.md](readtables.md))
  takes the CLtL2 path too (`holdsComma`).
- Template symbols are package-resolved against the DEFINING package (backquote quotes them
  before `PackageResolver` runs, [packages.md](packages.md)).
- The operators the reader writes itself are `cl`'s in any package: the instances in
  `ReaderSymbols` (`list`, `list*`, `cons`, `append`, `nconc`, `quote`, `function`, `lambda`),
  which `PackageResolver.resolveSymbolName` leaves alone. Identity decides, so the spelling stays
  bare for the passes ahead of the resolver and a copy of the symbol is a token again; a `cl:`
  spelling, which every such pass would see, was not tried. Measured 2026-11-04:
  every template shape called a shadowing definition, not only a dotted tail (`` `(:a ,x) `` called
  a shadowing `list`), `#'car` under a shadowed `function` was undefined, and a template in a
  package using no `cl` failed on the compile paths. Pin: ci-spec
  `reader-written-operators-ignore-the-package`. concrete-syntax-tree shadows `cons` and `list`,
  and loads on the interpreter since.

## `defmacro` per path
`CL_SPECIAL_FORMS`; cannot redefine a cl symbol; no function value.

- Interpreter: `LispEvaluator.userMacros` + `expandUserMacro`, from `evalCons` after the
  built-in switch.
- Compile path: `eval.UserMacroExpander.expand`, in `RontoLispCli.compileToFile` after
  `LoadInliner`, before the compilers. It evals `defmacro`s into a macro-time `LispEvaluator`,
  registers top-level `defun`s, expands every call site with a structure-aware walker (skips
  `quote`, `let`/`do` binding names, `lambda`/`defun` params, `case`-family keys,
  `dolist`/`dotimes` vars), and drops the definitions. A lambda list's NAMES stay but an
  `&optional`/`&key`/`&aux` default is an expression (`expandLambdaList`: `lambda`, `defun`,
  `defmethod`, a `defgeneric`'s `(:method ...)`, `flet`/`labels`, `destructuring-bind` with its
  nested patterns). Until 2026-10-31 the whole list stayed verbatim and a user macro there
  compiled "undefined function" on JVM and both WASM (lisp-binary's `read-bytes` `&key` default,
  a quasiquote-2.0 template). Pin: `UserMacroExpanderTest#lambdaListNamesStayButDefaultFormsExpand`. **Anything compiling without the CLI
  (corpus tests) must apply the pass itself.**
- Compiled output's `_eval`/`read` knows neither `defmacro` nor `` ` ``: define before use.

### Macro-time globals are LAZY
`defun`/`defclass`/`defgeneric`/`defmethod`/`define-condition`/`defstruct` register eagerly;
`defvar`/`defparameter`/`defconstant` go through `LispEvaluator.registerLazyGlobal` -- name
proclaimed special immediately, value parked as a thunk (`Environment.defineLazy`;
`lookup`/`lookupOrNull` force, `isBound` counts it bound, `define`/`set` discard, non-forcing
`hasBinding` serves `boundp`/`find-symbol` probes), run only if an expansion READS it.
`(ql:quickload "uax-15")` to a `.class`: lazy registration is dramatically faster than eager
and the output is byte-identical.
Forcing is reached from the macro-body read path AND the `#.` channel
([reader-features.md](reader-features.md)), hence its home in `Environment`. A failing value
expression is reported and leaves the name unbound. The thunk sees the evaluator as of the
FIRST READ and captures/restores the package of the DEFINITION
([symbol-runtime-api.md](symbol-runtime-api.md)). Not uax-15's lazy TABLES
([asdf.md](asdf.md)), which are a RUN-time deferral.

## A call site is expanded ONCE, on every backend
**Invariant: a user-macro call form is expanded once per source occurrence, not once per
evaluation — the interpreter included.** `LispEvaluator.userMacroExpansions`, an
`IdentityHashMap` keyed on the call site's cons, bounded by `EXPANSION_MEMO_LIMIT`, same shape
as the `compilerMacroExpansions`/`loadTimeValues` memos beside it (which only start hitting
because of it, [compiler-macros.md](compiler-macros.md)). Under a heavy macro-using system
like trivia the memo turns hundreds of repeated expansions into one per site.

- **Invalidation**: every write to `userMacros` goes through
  `LispEvaluator.putUserMacro`/`removeUserMacro`, which drop the WHOLE memo -- redefinition,
  `fmakunbound`, `(setf (symbol-function ...))`, a macro-function alias, `macrolet` entering
  or leaving scope.
- Its own monitor, never held across an expansion (a macro body re-enters the expander and may
  take the library load lock; calls are reachable from served requests,
  [concurrent-served-requests.md](concurrent-served-requests.md)). Racing threads both expand,
  last write wins.
- A macro body READING state while expanding now freezes the first answer -- what a compiled
  program always did; same shrinkage for [gensym-macroexpand.md](gensym-macroexpand.md).
  Built-in macros stay re-expanded per evaluation through `LispMacroExpander.expand*`.

## A macro body is a block named after the macro
`LispEvaluator.makeUserMacro` wraps a `defmacro`, `macrolet` or `define-compiler-macro` body in
`(block NAME ...)` only when it spells `(return-from NAME ...)`: a block is a catch per expansion, and
macro expansion is the compile paths' hot path. Before 2026-11-05 such a body failed on all four
backends (`RETURN-FROM: no enclosing block`); SBCL answers. ci-spec `eval-binds-macros-in-its-environment`.

## `destructuring-bind` + macro lambda lists (shared machinery)
`LispMacroExpander.expandDestructuringBind` (`CL_MACROS`; wired into the evaluator, all three
compilers, `FreeVarAnalyzer`, and the `rewriteLocalCalls`/`UserMacroExpander.expandAll`
pattern-keeping cases) turns the pattern into a `let*` of car/cdr chains over `__db<N>_whole`.
Keyword-free patterns reuse `destructurePairs` (shared with `loop`); a keyword-using pattern
binds the required prefix positionally then calls `LambdaLists.appendTailBindings` (incl. the
unknown-`&key` check as a `__ll_check` throwaway) over `__db<N>_r<i>`. Nested sub-patterns
recurse through `__db<N>_g<i>`; a dotted tail normalizes to `&rest`.

- **A pattern stands where ordinary syntax has a parameter name and no list of its own** (CLHS
  3.4.4.1): required, after `&rest`/`&body`, heading an `&optional` spec, the variable of
  `((keyword var) ...)`. `tailPatternsAsTemporaries` hands `appendTailBindings` a `__db<N>_g<i>` for
  each, and the pattern's bindings follow that temporary's, so a later default reads its variables
  (`(&rest (a b) &aux (c a))` read an unbound `a` until 2026-11-05) and a default destructures once,
  like a supplied value. A function's lambda list stays symbols (`LambdaLists.parse`). SBCL 2.2.9 is
  the reference for what CLHS leaves loose: `&aux` takes no pattern, a bare `()` under `&optional`
  ignores its position. `(&key ())`, which SBCL takes, is refused here. Consumer: eclector's
  `fixup-case`. `()` anywhere else is the pattern taking nothing, checked like any level
  (`(() b)` over `((1) 2)` bound 2 until then). Pin: ci-spec
  `destructuring-bind-and-defmacro-lambda-lists`, `LispEvaluatorTest#evalDestructuringBindNestedPatternWithKeywords`.
- **A surplus element signals; so does a missing one.** Both halves are the function
  lambda lists' `program-error` on the same message rail ([lambda-lists.md](lambda-lists.md),
  "A surplus element in a destructuring pattern" and "A missing element in a
  destructuring pattern").
- **`&whole` works in BOTH forms**: first pattern element binds the whole source list and the
  rest destructures it again; in a `defmacro` lambda list `evalDefmacro` binds
  `(cons 'name args)` and forces the destructuring path.
- **`&environment` in a MACRO lambda list is stripped by `makeUserMacro`** and bound by
  `expandMacroCall`: nil, or the symbol macros in scope for `macroexpand` to read
  (`.kb/symbol-macrolet.md`); still an error inside `destructuring-bind` itself. A
  `define-setf-expander`'s is its own, always nil.
- A non-simple `defmacro` lambda list (`isSimpleMacroLambdaList`) is stored rest-only
  (`__macro_args`) with the body wrapped in `destructuring-bind`, validated by a dry-run
  expansion; simple ones keep the strict arity check.

## `(setf (macro-function name) function)` — the macro table holds callables
**Invariant: a write that defines a macro goes to the MACRO TABLE, carried out by whichever pass
owns that table; only a write the program runs reaches a backend.**
`LispMacroExpander.isSetfMacroFunctionForm` recognizes it syntactically before the value form would
run (a setf of several pairs counts; `splitSetfPairs` makes it one-pair setfs first, so the other
places stay ordinary).

- Interpreter: `evalCons`'s `SETF` case -> `writeMacroFunction`. Name and value evaluate in the
  form's environment, a `UserMacro` with `expander` set goes in the table, and
  `expandMacroCall` calls it with the whole form and what an `&environment` parameter would hold
  (nil, or the symbol macros in scope, `.kb/symbol-macrolet.md`). `macro-function` answers that object
  (`eq`, as SBCL). A non-function value or non-symbol name is a `type-error` (SBCL 2.2.9), a `cl`
  name is refused like `defmacro`'s.
- Compile path: `UserMacroExpander.stripDefmacroDefinitions` replays it into the macro-time
  evaluator and DROPS it, like a `defmacro`: bare, a member of a `progn`/`eval-when` (a macro
  built it: log4cl's `forward-macro`, lparallel's `alias-macro`), or the body of a top-level
  `let`/`flet` of nothing but macro definitions (`bodyOnlyDefinesMacros`). At those positions
  `expandAll` (`topLevel`, `rebuildBinder`) returns it verbatim, so the value expands as the
  macro-time evaluator runs it: a walked `(macroexpand 'lit env)` would have folded without `env`.
  `writesMacroFunction` activates the pass at any depth. **A failed replay is a hard error naming
  the form** (`replayMacroDefinitions`): skipped with a warning, every call site would compile to
  an undefined function.
- **Anywhere else the program runs the write** (2026-11-04, all four backends; a compile error
  until then, parachute's `fixture.lisp:31` restore). `expandAll` walks its subforms where a
  `macrolet` is in scope, keeping the `setf` shape: a form that also replays
  (rutils' `abbr`, an `eval-when` around a `cond`) still reaches `writeMacroFunction` and its alias
  path. `expandSetf`'s `MACRO-FUNCTION` case lowers it to `(%set-macro-function name value)`,
  a prelude entry selected by surface (`usesRuntimeSetfMacroFunction`, also a pruner root): the
  interpreter's type-errors and `cl`-name refusal in its words, then a `%macro-function-writes%`
  alist. In such a program `emitMacroFunctionTable` emits a `macro-function` that reads the alist
  first and, where named, a `macroexpand`/`macroexpand-1` that run what it holds, so read-back is
  `eq` and a stored stub round-trips. Rejected: signalling at the call (breaks `abbr`'s kept
  form); evaluating and dropping the value (`macro-function` silently answers the old value).
  The `find-class` twin is `usesRuntimeSetfFindClass`, `.kb/clos.md`. `--no-gc` refuses it at the
  `defvar`.
- **A call does not see a run-time write on the compile paths**, as under SBCL's `compile-file`:
  every call was expanded when compiled, so a name only such a write defines compiles to an
  undefined function, with the warning. A literal `(macroexpand-1 '(name))` folds at compile time
  the same way. On the interpreter the call expands. Lite, compile paths: `fboundp` answers nil for
  a name only a run-time write defines, and `fmakunbound` leaves `macro-function` answering for a
  macro, written or defined (and `(fboundp 'macro)` is the compile-time `t` of `foldMacroFboundp`);
  SBCL 2.2.9 answers `t` and `nil`.
- `(setf (macro-function 'a) (macro-function 'b))` answers the function, also through the alias path
  (it answered the name until 2026-11-04), as SBCL does.
- **The function `macro-function` answers for a `defmacro` is the one it had when asked**: it
  captures the `UserMacro`, not the name. Over the name, storing it back after a redefinition
  (parachute's fixture restore) expanded through itself forever. Such a call runs the expander
  each time and does not touch the per-site memo.

The literal alias `(setf (macro-function 'new) (macro-function 'existing))`
(`macroFunctionArgumentName`) stays its own path, because the function value could not reach
two of its cases: lookup is package-tolerant (`lookupUserMacro`: exact -> qualified member ->
unique member), and a BUILT-IN macro target (`runtimeMacroNames`) has no expander to share, so
the alias is a synthesized `(&rest args) (cons 'TARGET args)` user macro; the new name may be a
keyword -- rutils' `(abbr :+ incf)`, h58. An alias of a non-macro says `is not a macro`.
Class-name twin: `(setf (find-class ...))`, [clos.md](clos.md).

## The macro-time evaluator is the "compiling image"
It used to under-load: only definitions registered, so a library building a macro-consulted
registry with plain top-level CALLS was invisible (trivia's `(set-vector-matcher ...)`).

- **Spliced-system replay** (`UserMacroExpander.replayLibraryTopLevel`): inside the
  `(%begin-system ...)`/`(%end-system)` brackets
  ([library-defun-pruning.md](library-defun-pruning.md)) every plain top-level form is
  EVALUATED into the macro-time evaluator -- progn walked member-wise, the defvar family still
  lazy, failures warned and skipped. USER-file forms (depth 0) keep compile-file semantics, so
  a user's own side effect is never double-run; replay output is discarded and the forms stay
  in the program.
- **A macro-EXPANDED `(eval-when ...)` honors its situations**
  (`UserMacroExpander.processExpandedEvalWhen`; source-level top-level ones are flattened
  earlier): `:compile-toplevel` replays the member, `:load-toplevel` keeps it, a defmacro
  member is consumed, a nested eval-when recurses, a neither-situation member is dropped.
  Since h57 also `:execute` in compile-time-too mode (named-readtables' `defreadtable` is
  `(eval-when (:load-toplevel :execute) ...)`), and inside a spliced system a
  `:load-toplevel`/`:execute` member replays like a plain form. **A SOURCE-level `eval-when`**
  whose situations evaluate at compile time has its members recorded by
  `flattenTopLevel(program, compileTime)` and evaluated (`Session.evalAtCompileTime`, a defvar
  eagerly) -- before h57 only their definitions registered ([readtables.md](readtables.md)).
- **The members of an expansion are top-level forms, in order** (`expandTopLevelMembers`, CLHS
  3.2.3.1, through `progn`/`locally`/`eval-when`/`macrolet`): each is walked once the one before it
  has registered its definitions, so `(progn (defmacro m ...) (m))` reaches `m`. Lite: the
  compile-time half of an expanded `eval-when` still runs after the whole walk, so a macro in a later
  member cannot call a function that member defines.
- **`defsetf`, `define-setf-expander`, `define-compiler-macro` and `define-modify-macro` a macro
  expands to are consumed like a `defmacro`**, and the walk keeps them verbatim
  (`isExpansionTimeDefinition`): nibbles' `define-storer` is `(progn (defun ...) (defsetf ...))`,
  which compiled to a call of `DEFSETF`.
- **A `defmacro` a macro EXPANDS to is consumed wherever it lands**
  (`stripDefmacroDefinitions`): directly, inside the `progn` the macro wrapped around it
  (recursing through `progn`/`eval-when`, dropping an emptied wrapper, mirroring
  `stripSymbolMacroDefinitions`), and inside a top-level `let`/`let*`/`flet`/`labels` that
  CLOSES OVER it -- the whole form is replayed so the definition keeps its closure and the
  gensym inits run once, gated on the body being macro definitions only (a quoted value after
  the first one is allowed: fiveam's `def-special-environment` ends its `(flet () ...)` with
  `',name`). **Trap: a definition left in place compiles to a call of the undefined function
  `DEFMACRO`, and every call site of the macro it should have defined fails at run time.**
  An expanded top-level `locally` is the `progn` it is (`topLevelLocallyAsProgn`, hu.dwim.def's
  `(def macro ...)`), as `flattenTopLevel` treats a source's.
  Pinned by `UserMacroExpanderTest#macroExpandingToDefmacroIsConsumedToo`.

`define-compiler-macro` reuses `makeUserMacro`, `&whole`/`&environment` and `expandMacroCall`
but lives in its own table ([compiler-macros.md](compiler-macros.md)).

## Tests
- `LispReaderTest.readBackquote*`/`readNestedBackquote*`, `LispLexerTest`.
- `LispEvaluatorTest`: `evalCommaDotSplicesLikeCommaAt`, `defmacroNestedBackquoteOnceOnly`,
  `userMacroExpandsOncePerCallSite`, `redefiningAMacroReexpandsItsCallSites`,
  `macroletEnteringAndLeavingScopeInvalidatesTheExpansionMemo`,
  `setfMacroFunction{AliasesAUserMacro,InstallsAnExpanderFunction,RejectsWhatIsNotAnExpander}`,
  plus the defmacro and
  destructuring-bind sections.
- `UserMacroExpanderTest` (`setfMacroFunction*`); `JvmLispCompilerTest#compileAndRun{UserMacroAfterExpansionPass,
  DestructuringBind,UserMacroWithDestructuringLambdaList,NestedBackquoteOnceOnly,
  SetfMacroFunctionAliasAfterExpansionPass}` and `WasmLispCompilerIntegrationTest`'s twins;
  `TriviaE2eTest`.
- ci-spec `backquote-quoted-splice`, `trivia-enablement-language-group`,
  `sharp-l-comma-dot-and-hash-table-iterator`, `nested-backquote-once-only`,
  `defmacro-user-macros`, `destructuring-bind-and-defmacro-lambda-lists`,
  `array-operations-enablement-language-group`. Follow-ups in `.todo/044`.
