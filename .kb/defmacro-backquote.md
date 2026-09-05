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
- Nested backquote: `readBackquote` raw-reads first (`readRawTemplate`) to detect an inner
  `` ` ``; non-nested keeps the optimized single-level expander, nested goes through the
  CLtL2/Steele Appendix C port (`bqCompletelyProcess` = `bqProcess` + `bqSimplify` +
  `bqRemoveTokens`, over identity-compared `BQ_*` sentinels). Every level expands at READ
  time. One deviation: an inner backquote escaping to level 0 is expanded in place by
  `bqExpandEscaped`, the runtime having no backquote.
- Template symbols are package-resolved against the DEFINING package (backquote quotes them
  before `PackageResolver` runs, [packages.md](packages.md)).

## `defmacro` per path
`CL_SPECIAL_FORMS`; cannot redefine a cl symbol; no function value.

- Interpreter: `LispEvaluator.userMacros` + `expandUserMacro`, from `evalCons` after the
  built-in switch.
- Compile path: `eval.UserMacroExpander.expand`, in `RontoLispCli.compileToFile` after
  `LoadInliner`, before the compilers. It evals `defmacro`s into a macro-time `LispEvaluator`,
  registers top-level `defun`s, expands every call site with a structure-aware walker (skips
  `quote`, `let`/`do` binding names, `lambda`/`defun` params, `case`-family keys,
  `dolist`/`dotimes` vars), and drops the definitions. **Anything compiling without the CLI
  (corpus tests) must apply the pass itself.**
- Compiled output's `_eval`/`read` knows neither `defmacro` nor `` ` ``: define before use.

### Macro-time globals are LAZY
`defun`/`defclass`/`defgeneric`/`defmethod`/`define-condition`/`defstruct` register eagerly;
`defvar`/`defparameter`/`defconstant` go through `LispEvaluator.registerLazyGlobal` -- name
proclaimed special immediately, value parked as a thunk (`Environment.defineLazy`;
`lookup`/`lookupOrNull` force, `isBound` counts it bound, `define`/`set` discard, non-forcing
`hasBinding` serves `boundp`/`find-symbol` probes), run only if an expansion READS it.
`(ql:quickload "uax-15")` to a `.class`: 86.5 s eager vs 3.6 s lazy, byte-identical output.
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
because of it, [compiler-macros.md](compiler-macros.md)). A 177-expansion `(fib 10)` under
trivia went 17,385 ms -> 115 ms.

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

## `destructuring-bind` + macro lambda lists (shared machinery)
`LispMacroExpander.expandDestructuringBind` (`CL_MACROS`; wired into the evaluator, all three
compilers, `FreeVarAnalyzer`, and the `rewriteLocalCalls`/`UserMacroExpander.expandAll`
pattern-keeping cases) turns the pattern into a `let*` of car/cdr chains over `__db<N>_whole`.
Keyword-free patterns reuse `destructurePairs` (shared with `loop`); a keyword-using pattern
binds the required prefix positionally then calls `LambdaLists.appendTailBindings` (incl. the
unknown-`&key` check as a `__ll_check` throwaway) over `__db<N>_r<i>`. Nested sub-patterns
recurse through `__db<N>_g<i>`; a dotted tail normalizes to `&rest`.

- **Lite semantics: NO mismatch errors** (missing -> nil, surplus ignored).
- **`&whole` works in BOTH forms**: first pattern element binds the whole source list and the
  rest destructures it again; in a `defmacro` lambda list `evalDefmacro` binds
  `(cons 'name args)` and forces the destructuring path.
- **`&environment` in a MACRO lambda list is stripped and bound to nil** by `makeUserMacro`,
  so a macro that merely PASSES it on works and one expecting a real environment does not;
  still an error inside `destructuring-bind` itself.
- A non-simple `defmacro` lambda list (`isSimpleMacroLambdaList`) is stored rest-only
  (`__macro_args`) with the body wrapped in `destructuring-bind`, validated by a dry-run
  expansion; simple ones keep the strict arity check.

## `(setf (macro-function 'new) (macro-function 'existing))` — macro aliases
**Invariant: a macro alias is a write to the MACRO TABLE, carried out by whichever pass owns
that table; the form never reaches a backend.** Recognized SYNTACTICALLY before the value form
would run: `LispMacroExpander.isSetfMacroFunctionForm` + `macroFunctionArgumentName`.
Interpreter `evalCons`'s `SETF` case -> `aliasMacroFunction`; compile path
`UserMacroExpander.expand` replays into the macro-time evaluator and DROPS it, with the
predicate in the pass's activation list (an alias-only program must still activate it) and
`expandAll`'s `SETF` case returning the shape verbatim. Reaching `expandSetf`'s
`MACRO-FUNCTION` case means neither interception applied; it throws. Lookup is
package-tolerant (`lookupUserMacro`: exact -> qualified member -> unique member). Open: an
arbitrary expander FUNCTION is rejected -- `macro-function` builds a `LispFunction` on demand
rather than storing one, so supporting it means the macro table must hold callables.
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
- **A `defmacro` a macro EXPANDS to is consumed wherever it lands**
  (`stripDefmacroDefinitions`): directly, inside the `progn` the macro wrapped around it
  (recursing through `progn`/`eval-when`, dropping an emptied wrapper, mirroring
  `stripSymbolMacroDefinitions`), and inside a top-level `let` that CLOSES OVER it -- the
  whole `let` is replayed so the definition keeps its closure and the gensym inits run once,
  gated on the body being macro definitions only. **Trap: a definition left in place compiles
  to a call of the undefined function `DEFMACRO`, and every call site of the macro it should
  have defined fails at run time.**

`define-compiler-macro` reuses `makeUserMacro`, `&whole`/`&environment` and `expandMacroCall`
but lives in its own table ([compiler-macros.md](compiler-macros.md)).

## Tests
- `LispReaderTest.readBackquote*`/`readNestedBackquote*`, `LispLexerTest`.
- `LispEvaluatorTest`: `evalCommaDotSplicesLikeCommaAt`, `defmacroNestedBackquoteOnceOnly`,
  `userMacroExpandsOncePerCallSite`, `redefiningAMacroReexpandsItsCallSites`,
  `macroletEnteringAndLeavingScopeInvalidatesTheExpansionMemo`,
  `setfMacroFunction{AliasesAUserMacro,RejectsNonAliasShapes}`, plus the defmacro and
  destructuring-bind sections.
- `UserMacroExpanderTest`; `JvmLispCompilerTest#compileAndRun{UserMacroAfterExpansionPass,
  DestructuringBind,UserMacroWithDestructuringLambdaList,NestedBackquoteOnceOnly,
  SetfMacroFunctionAliasAfterExpansionPass}` and `WasmLispCompilerIntegrationTest`'s twins;
  `TriviaE2eTest`.
- ci-spec `backquote-quoted-splice`, `trivia-enablement-language-group`,
  `sharp-l-comma-dot-and-hash-table-iterator`, `nested-backquote-once-only`,
  `defmacro-user-macros`, `destructuring-bind-and-defmacro-lambda-lists`,
  `array-operations-enablement-language-group`. Follow-ups in `.todo/044`.
