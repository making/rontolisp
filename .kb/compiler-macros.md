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
- **Decline must terminate**: the idiom returns `&whole`, a fresh cons an identity test reads
  as progress (infinite re-expansion). `computeCompilerMacroExpansion` compares `print()`
  output and returns the ORIGINAL object on equality.
- **A signalling body must not fail the compile**: `RuntimeException`/`StackOverflowError`
  leaves the call alone; likewise a `(setf name)` designator, a rejected lambda list, and a
  standard operator (never registered).
- **Expansion-time output is suppressed everywhere**: compile path writes to a null stream,
  `LispEvaluator` mutes `MutablePrintStream` around the expansion.
- Not implemented: `notinline` (still rewritten), `compiler-macro-function`.
  `LispMacroExpander.expandDefineCompilerMacro` survives as the `--no-gc` nil lowering and for
  `macroexpand-1` of a DEFINITION form.

## `load-time-value`
`LispMacroExpander.hoistLoadTimeValues`, first line of `expandTopLevelDefinitions`; each
occurrence becomes, with the defvar prepended at index 0:

```lisp
(car (or %LOAD-TIME-VALUE-1 (setq %LOAD-TIME-VALUE-1 (list <value form>))))
```

The one-element LIST makes a `nil` result count as computed; names numbered from 1 in walk
order ([emitted-output-determinism.md](emitted-output-determinism.md)). Interpreter
`evalLoadTimeValue` memoizes on cons identity. Both memos cap at `EXPANSION_MEMO_LIMIT`
(20,000), then recompute.

- **The fill is LAZY, a deliberate deviation from CL**: the value form needs its library's
  globals, initialized by LATER top-level forms. An occurrence never reached is never evaluated.
- **The top-level `(defvar %LOAD-TIME-VALUE-N nil)` is mandatory**: both compilers build their
  global set from `topLevelExprs`, which EXCLUDES `defun` bodies.
- `worthHoistingLoadTimeValue` hoists only a CALL; `quote`/`function`/`find-package` wrappers
  must stay in place so `LispMacroExpander.literalPackageDesignator` can fold
  `(load-time-value (find-package :ironclad))` to a compile-time package name.

## Tests
- `LispEvaluatorTest.evalDefineCompilerMacroRewritesCallSites`,
  `.evalDefmacroWinsOverCompilerMacro`, `.loadTimeValueEvaluatesOncePerOccurrence`
- `JvmLispCompilerTest.compileAndRunDefineCompilerMacroRewritesCallSites`, `.compileAndRunLoadTimeValue`
- `WasmLispCompilerIntegrationTest.defineCompilerMacroAndRestartCase`, `.shiftfAndLoadTimeValue`
- ci-spec `macrolet-compiler-macro-restart-case` (all four backends)
