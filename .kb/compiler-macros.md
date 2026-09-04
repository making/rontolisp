# Compiler macros and `load-time-value`

**Invariant: a library's own `define-compiler-macro` runs, and `load-time-value` evaluates
once per source occurrence — identically on the interpreter, the JVM and both WASM GC
backends.** They only pay off together: the library idiom is `(constantp arg)` → rewrite so
the expensive argument becomes `(load-time-value (build-it ...))`; a compiler macro without a
real `load-time-value` moves the cost rather than removing it (cl-ppcre's eight
`define-compiler-macro`s are the motivating case).

They do NOT reduce module SIZE: a fired rewrite ADDS ~179 B (the `load-time-value` slot) and
removes nothing (the builder still ships to run at load time); on a call whose regex is a
variable they do not fire at all (`.kb/optimize-dead-code-elimination.md`).

## `define-compiler-macro`

Coexists with a function of the same name, so it lives in its OWN table, never `userMacros`:
`LispEvaluator.compilerMacros`, filled by `evalDefineCompilerMacro` through the same
`makeUserMacro` the `defmacro` path uses (`&whole`, `&environment` — bound to nil — and every
extended lambda list shape work). `PackageResolver` treats a `define-compiler-macro` body as
TEMPLATE context alongside `defmacro`/`defsetf`/`define-setf-expander`, so the re-emitted
function name resolves in the DEFINING package.

Applied once per call site, after every other operator has had its chance:

- **Interpreter** — `evalCons`, after the built-in switch, the car/cdr composition check and
  the `userMacros` check (a `defmacro` of the same name wins, as CL requires). Memoized in
  `compilerMacroExpansions`, an `IdentityHashMap` keyed on the CALL FORM: one expansion
  object per source occurrence, which is what makes the `load-time-value` memo inside it hit.
  The interpreter also memoizes user-macro expansion by call-site identity
  (`.kb/defmacro-backquote.md`, "A call site is expanded ONCE") — without that, a literal
  regex inside `do-matches` got a fresh `(scan …)` cons per iteration and missed both memos.
- **Compile path** — `UserMacroExpander.expandAll`, right after the user-macro loop. The
  top-level loop registers the definition and DROPS the form (like `defmacro`); backends
  never see one. `UserMacroExpander.expand`'s activation gate lists `DEFINE_COMPILER_MACRO`,
  or a program whose only macro facility is a compiler macro would skip the pass and diverge
  from the interpreter.
- **`--no-gc`** — `NoGcWasmCompiler` keeps the nil no-op.

Three load-bearing properties, each a real prior failure:

1. **Decline must terminate.** The universal decline idiom returns the `&whole` parameter, a
   FRESHLY consed copy — an identity test takes it for progress and re-expands forever
   (interpreter `StackOverflowError`; compile path a silent hang).
   `computeCompilerMacroExpansion` compares `print()` output and returns the ORIGINAL object
   on equality; application runs at most once per site.
2. **A signalling body must not fail the compile.** CLHS permits ignoring a compiler macro,
   so `RuntimeException`/`StackOverflowError` from the body is caught and the call left alone
   (ironclad's `make-digest` reaches for package objects at expansion time). The same
   permission covers a `(setf name)` designator, a lambda list the macro machinery rejects,
   and a standard operator (never registered — the shared expander lowers `cl:` operators
   first, so registering one would split interpreter vs compiler).
3. **Expansion-time output is suppressed on every backend.** cl-utilities'
   `partition`/`partition-if`/`partition-if-not` `warn` then decline. The compile path
   already swallows it (macro-time evaluator writes to a null stream); `LispEvaluator` wraps
   its output in `MutablePrintStream` and mutes it around a compiler-macro expansion.

Not implemented: `notinline` (a call declared `notinline` is still rewritten) and
`compiler-macro-function`; neither is used by any loadable library.
`LispMacroExpander.expandDefineCompilerMacro` survives as the nil lowering for `--no-gc` and
for `macroexpand-1` of a DEFINITION form (`expandBuiltinMacro`'s case list stays in sync with
`PackageRegistry.CL_MACROS`); a macro CALL is correctly not expanded by `macroexpand`.

## `load-time-value`

- **Compile path** — `LispMacroExpander.hoistLoadTimeValues`, invoked from the first line of
  `expandTopLevelDefinitions`, the one whole-program pass both compilers already call
  (`JvmLispCompiler` / `WasmLispCompiler`), so it needs no registration in the CLI,
  playground, tree-shaker tests or ASDF E2E harness. Each occurrence becomes

  ```lisp
  (defvar %LOAD-TIME-VALUE-1 nil)                       ; prepended at index 0
  (car (or %LOAD-TIME-VALUE-1 (setq %LOAD-TIME-VALUE-1 (list <value form>))))
  ```

  The slot holds a one-element LIST so a `nil` result still counts as computed. Names are
  numbered from 1 in walk order (`.kb/emitted-output-determinism.md`).
- **Interpreter** — `evalLoadTimeValue` memoizes on the form's cons identity.

Both memos are capped at 20,000 entries (`EXPANSION_MEMO_LIMIT`); past the cap the expansion
is recomputed.

**The fill is LAZY, not at program start — a deliberate deviation from CL.** The value form is
routinely spliced out of a library and needs that library's own globals, initialized by later
top-level forms (cl-ppcre's `create-scanner` reads `*standard-optimize-settings*`,
`*allow-quoting*`, `*regex-char-code-limit*`). Evaluating at index 0 would read them unbound.
An occurrence never reached is never evaluated.

**A top-level `(defvar %LOAD-TIME-VALUE-N nil)` is mandatory, a nested `setq` is not enough**:
both compilers build their global set from `topLevelExprs`, which EXCLUDES `defun` forms, so
a `setq` of an undeclared name inside a function body would get no backing store.

**Not every occurrence is hoisted, and the exemption is not an optimization.**
`worthHoistingLoadTimeValue` hoists only a CALL. An atom or variable read costs nothing to
repeat; and `quote` / `function` / `find-package` wrappers must stay literally in place,
because `LispMacroExpander.literalPackageDesignator` folds
`(load-time-value (find-package :ironclad))` into a compile-time package name — a hoist would
hide the literal and ironclad's `intern`/`find-symbol` would build doubly-qualified symbols
at runtime.

## Tests
- `LispEvaluatorTest.evalDefineCompilerMacroRewritesCallSites`,
  `.evalDefmacroWinsOverCompilerMacro`, `.loadTimeValueEvaluatesOncePerOccurrence`
- `JvmLispCompilerTest.compileAndRunDefineCompilerMacroRewritesCallSites`,
  `.compileAndRunLoadTimeValue`
- `WasmLispCompilerIntegrationTest.defineCompilerMacroAndRestartCase`, `.shiftfAndLoadTimeValue`
- `ci-spec.yaml` case `macrolet-compiler-macro-restart-case` (all four backends)
