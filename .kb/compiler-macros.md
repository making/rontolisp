# Compiler macros and `load-time-value`

**Invariant: a library's own `define-compiler-macro` runs, and `load-time-value` evaluates
once per source occurrence — identically on the interpreter, the JVM and both WASM GC
backends.** They only pay off together (`(constantp arg)` → rewrite the expensive argument
into `(load-time-value (build-it ...))`). They do NOT reduce module SIZE: a fired rewrite
ADDS ~179 B and removes nothing.

## `define-compiler-macro`
Own table `LispEvaluator.compilerMacros` (`evalDefineCompilerMacro` → the same
`makeUserMacro` as `defmacro`), because a function of the same name coexists.
`PackageResolver` treats its body as TEMPLATE context alongside
`defmacro`/`defsetf`/`define-setf-expander`. Applied once per call site, last: interpreter
`evalCons` after the built-in switch and `userMacros` (a `defmacro` of the same name wins),
compile path `UserMacroExpander.expandAll` after the user-macro loop; `--no-gc` keeps the nil
no-op.

- Interpreter memo `compilerMacroExpansions` is an `IdentityHashMap` keyed on the CALL FORM —
  that identity is what makes the inner `load-time-value` memo hit, and it depends on the
  interpreter's own call-site user-macro memo ([defmacro-backquote.md](defmacro-backquote.md)).
- The compile path's top-level loop registers and DROPS the form; backends never see one.
  `DEFINE_COMPILER_MACRO` must stay in `UserMacroExpander.expand`'s activation gate, or a
  program whose only macro facility is a compiler macro skips the pass and diverges.
- **Decline must terminate**: the idiom returns `&whole`, the call form itself
  ([defmacro-backquote.md](defmacro-backquote.md)); `computeCompilerMacroExpansion` also
  compares `print()` output and returns the ORIGINAL object on equality, for an expander that
  rebuilds the call.
- **A signalling body must not fail the compile**: `RuntimeException`/`StackOverflowError`
  leaves the call alone; likewise a `(setf name)` designator, a rejected lambda list, and a
  standard operator (never registered).
- **Expansion-time output is suppressed everywhere**: compile path writes to a null stream,
  `LispEvaluator` mutes `MutablePrintStream` around the expansion.
- **`notinline` in a `locally`** (CLHS 3.2.2.1.3): `LispEvaluator.markNotinlineCalls` records the
  body's calls of a declared name that has a compiler macro, by identity, and
  `computeCompilerMacroExpansion` declines them; the interpreter's `locally` arm and
  `UserMacroExpander.expandAllLocated` call it. spinneret's `html` expands to
  `(locally (declare (notinline html)) (html ...))`, which re-expanded forever until 2026-11-07.
  Not covered: a `declare` in any other body, a global `declaim`, a local function of the name.
- `compiler-macro-function`: the interpreter answers the real expander off `compilerMacros`;
  the compile path answers `macro-function`'s `%macro-expander-stub` for a registered name
  (`UserMacroExpander.emitCompilerMacroFunctionTable` appends the defun when the program names
  the function and has a compiler macro), nil otherwise (prelude defun). documentation-utils'
  `#'compiler-macro-function` documentation test is the consumer.
- `LispMacroExpander.expandDefineCompilerMacro` survives as the `--no-gc` nil lowering and for
  `macroexpand-1` of a DEFINITION form.

## `(setf (compiler-macro-function name [env]) function)`
An ordinary call on every consumer, unlike `(setf macro-function)`: `expandSetf` lowers it to
`(%set-compiler-macro-function name value)` (an env subform evaluates through a `prog1` on the name).

- Interpreter: `writeCompilerMacroFunction` puts a `UserMacro` with `expander` set in
  `compilerMacros` (nil removes) and clears the call-site memo (`define-compiler-macro` clears it
  too). `compiler-macro-function` answers the stored function itself; for a defined one, a function
  capturing the `UserMacro` it had when asked, so a wrapper stored back does not reach itself.
  A `(setf name)` name and a `cl` symbol are accepted and not registered; a non-function non-nil
  value is a `type-error`.
- Trap, fixed with this: `ensurePreludeSetfPlacesLoaded` loaded the PRELUDE entry of a setf place
  head even when the interpreter defines the name itself, so the first such setf replaced the
  interpreter's `compiler-macro-function` with the compile paths' nil-answering defun. It now skips
  a name the global environment defines.
- Compile paths: the prelude entry (selected by `usesRuntimeSetfCompilerMacroFunction`, a pruner
  root) writes `%compiler-macro-function-writes%`; in such a program
  `emitCompilerMacroFunctionTable` emits a `compiler-macro-function` reading the alist first (the
  setf also activates the pass). Compiled call sites do not change, as under `compile-file`,
  EXCEPT the static case: a top-level `(setf (compiler-macro-function 'f) <lambda | #'g | nil>)`
  (`isStaticCompilerMacroWrite`) also evaluates into the macro-time evaluator, installing it for
  the call sites after it, and stays in the program. A spliced library's write runs there anyway
  (replay, `eval-when`): static-dispatch's `add-compiler-macro` installs `#'static-dispatch` for
  generic-cl's later call sites.
- Consumers: static-dispatch 20211209 (`common.lisp:294`), cl-environments 20250622
  (`cl-overrides.lisp:194`). Measured 2026-11-07: `(ql:quickload :generic-cl)` loads on the
  interpreter and a call runs through its compiler macro; the JVM compile of cl-environments
  completes and runs; and generic-cl's `(generic-cl:+ 1 2)` prints `3` on all four
  ([packages.md](packages.md), "The compile image's packages").

## `load-time-value`
`LispMacroExpander.hoistLoadTimeValues`, first line of `expandTopLevelDefinitions`; each
occurrence becomes, with the one vector's defvar prepended at index 0:

```lisp
(defvar %LOAD-TIME-VALUE-SLOTS (make-array N :initial-element nil))
(car (or (aref %LOAD-TIME-VALUE-SLOTS 0) (%aset %LOAD-TIME-VALUE-SLOTS 0 (list <value form>))))
```

The one-element LIST makes a `nil` result count as computed; slots numbered from 0 in walk
order ([emitted-output-determinism.md](emitted-output-determinism.md)). An occurrence is keyed by
identity, as the interpreter's `evalLoadTimeValue` keys it: one form in two places is one slot
([make-load-form.md](make-load-form.md)). The interpreter's memos cap at `EXPANSION_MEMO_LIMIT`
(20,000), then recompute.

- **One vector, not a defvar per occurrence** (2026-11-06): `make-load-form`'s dumps are
  occurrences, 1.2 million in coalton's library, and each defvar was a special: an arm of every
  by-name dispatch (`%global-access`, progv, `symbol-value`), three JVM pool entries, a wasm global
  (wasmtime refuses a module past 1,000,000). A fragment's vector is its own: the name stays out of
  the JVM link table (`JvmLinkage`).
- **The fill is LAZY, a deliberate deviation from CL**: the value form needs its library's
  globals, initialized by LATER top-level forms. An occurrence never reached is never evaluated.
- **The top-level defvar is mandatory**: both compilers build their global set from
  `topLevelExprs`, which EXCLUDES `defun` bodies.
- `worthHoistingLoadTimeValue` hoists only a CALL; `quote`/`function`/`find-package` wrappers
  must stay in place so `LispMacroExpander.literalPackageDesignator` can fold
  `(load-time-value (find-package :ironclad))` to a compile-time package name.

## Tests
- `LispEvaluatorTest.evalDefineCompilerMacroRewritesCallSites`,
  `.evalDefmacroWinsOverCompilerMacro`, `.loadTimeValueEvaluatesOncePerOccurrence`
- `JvmLispCompilerTest.compileAndRunDefineCompilerMacroRewritesCallSites`, `.compileAndRunLoadTimeValue`
- `WasmLispCompilerIntegrationTest.defineCompilerMacroAndRestartCase`, `.shiftfAndLoadTimeValue`
- ci-spec `macrolet-compiler-macro-restart-case`, `makunbound-and-compiler-macro-function` (incl.
  the static and run-time `setf`; all four backends)
