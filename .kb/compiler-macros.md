# Compiler macros and `load-time-value`

**Invariant: a library's own `define-compiler-macro` runs, and `load-time-value`
evaluates once per source occurrence — identically on the interpreter, the JVM and
both WASM GC backends.** Both were parsed no-ops before 2026-07-26, and they only
pay off together: the library idiom is `(constantp arg)` → rewrite the call so the
expensive argument becomes `(load-time-value (build-it ...))`, so a compiler macro
without a real `load-time-value` moves the cost rather than removing it.

Pinned by `LispEvaluatorTest.evalDefineCompilerMacroRewritesCallSites` /
`evalDefmacroWinsOverCompilerMacro` / `loadTimeValueEvaluatesOncePerOccurrence`,
`JvmLispCompilerTest.compileAndRunDefineCompilerMacroRewritesCallSites` /
`compileAndRunLoadTimeValue`,
`WasmLispCompilerIntegrationTest.defineCompilerMacroAndRestartCase` /
`shiftfAndLoadTimeValue`, and the `macrolet-compiler-macro-restart-case` ci-spec case
(all four backends end to end).

## Why it matters: the measurement

cl-ppcre's entry points take a regex designator, and its `create-scanner` COMPILES the
pattern. Upstream gets one scanner per literal call site for free from eight
`define-compiler-macro`s that all read

```lisp
(cond ((constantp regex) `(split (load-time-value (create-scanner ,regex)) ,target ,@rest))
      (t form))
```

With those dropped, the scanner was rebuilt on every call — and, inside the
`do-scans` family, on every ITERATION, because `do-scans` deliberately declines to
hoist a constant regex ("SCAN's compiler macro will take care of them"). Measured on
`(cl-ppcre:split ";" line)` × 3,000 against a manually hoisted `create-scanner`
control:

| backend | literal, before | literal, after | hoisted control |
| --- | --- | --- | --- |
| interpreter | 47,800 ms | **6,608 ms** | 6,413 ms |
| JVM | 131 ms | **55 ms** | 13 ms (JIT noise at this size) |
| WASM `--component` | 246 ms | **136 ms** | 140 ms |

The isolated shapes are worse than the `split` ratio suggests, because `split`
amortizes one scanner over 16 scans: `(cl-ppcre:scan "…" line)` was 146x the hoisted
control on the interpreter and `do-matches-as-strings` 80x. After the change the
three compiled backends are at or below the hoisted control on every shape.

What they are NOT worth is module SIZE: a fired rewrite ADDS ~179 B (the
`load-time-value` slot) and removes nothing, because the scanner BUILDER still
ships to run at load time — and on a call whose regex is a variable or computed
(tiny-routes' whole routing path) they do not fire at all, measured
byte-identical with all eight stripped
(`.kb/optimize-dead-code-elimination.md`, "What ROUTING costs a clack module").

**The one gap left was interpreter-side and had a different cause**, closed on
2026-08-25: the interpreter re-expanded a user macro on every evaluation, so a literal
regex inside `do-matches` got a FRESH `(scan …)` cons per iteration, which missed the
per-call-site memos below and rebuilt the scanner anyway (11.1 s vs 0.75 s for 500
iterations). The interpreter now memoizes macro expansion by call-site identity too, so
these two memos see one call form per source occurrence and hit: the same 500 iterations
measure 1.29 s — [defmacro-backquote.md](defmacro-backquote.md), "A call site is expanded
ONCE".

## `define-compiler-macro` — registration and application

A compiler macro coexists with a function of the same name, so it lives in its OWN
table, never `userMacros`: `LispEvaluator.compilerMacros`, filled by
`evalDefineCompilerMacro` through the same `makeUserMacro` the `defmacro` path uses
(so `&whole`, `&environment` — bound to nil — and every extended lambda list shape
work identically). `PackageResolver` treats a `define-compiler-macro` body as TEMPLATE
context alongside `defmacro`/`defsetf`/`define-setf-expander`, so the function name the
expansion re-emits resolves in the DEFINING package.

Application, once per call site, after every other operator has had its chance:

- **Interpreter** — `evalCons`, after the built-in switch, the car/cdr composition
  check and the `userMacros` check (so a `defmacro` of the same name wins, as CL
  requires). Memoized in `compilerMacroExpansions`, an `IdentityHashMap` keyed on the
  CALL FORM: the expansion is built once per source occurrence, which is both the
  point of the optimization and what makes the `load-time-value` memo hit — the cached
  expansion is one object, so the `(load-time-value …)` inside it is one object.
- **Compile path** — `UserMacroExpander.expandAll`, right after the user-macro loop.
  The top-level loop registers the definition and DROPS the form (like `defmacro`);
  the backends never see one. `UserMacroExpander.expand`'s activation gate lists
  `DEFINE_COMPILER_MACRO`, or a program whose only macro facility is a compiler macro
  would skip the pass and diverge from the interpreter.
- **`--no-gc`** — `NoGcWasmCompiler` keeps the nil no-op: the numeric subset never
  loads a library that ships one.

Three properties are load-bearing, and each was a real failure before it was written:

1. **Decline must terminate.** The universal decline idiom returns the `&whole`
   parameter, which is a FRESHLY consed copy of the call — an identity test takes it
   for progress and re-expands forever (interpreter: `StackOverflowError`; compile
   path: a silent hang with no diagnostic). `computeCompilerMacroExpansion` compares
   `print()` output and returns the ORIGINAL object on equality, and application runs
   at most once per site.
2. **A signalling body must not fail the compile.** ironclad's `make-digest` compiler
   macro reaches for package objects at expansion time. CLHS explicitly permits
   ignoring a compiler macro, so `RuntimeException`/`StackOverflowError` from the body
   is caught and the call left alone. The same permission covers a `(setf name)`
   designator, a lambda list the macro machinery rejects, and a standard operator
   (never registered — the shared expander lowers `cl:` operators before a compiler
   macro could see them, so registering one would produce an interpreter/compiler
   split).
3. **Expansion-time output is suppressed on every backend.** cl-utilities'
   `partition`/`partition-if`/`partition-if-not` compiler macros `warn` and then
   decline. The compile path already swallowed that (its macro-time evaluator writes to
   a null stream), so without muting the interpreter the same program printed a
   warning on one backend and not the others. `LispEvaluator` wraps its output in
   `MutablePrintStream` and mutes it around a compiler-macro expansion.

Not implemented: `notinline` (a call declared `notinline` is still rewritten) and
`compiler-macro-function`. Neither is used by any loadable library.
`LispMacroExpander.expandDefineCompilerMacro` survives as the nil lowering for
`--no-gc` and for `macroexpand-1` of a DEFINITION form (`expandBuiltinMacro`'s case
list is kept in sync with `PackageRegistry.CL_MACROS`); a macro CALL is correctly not
expanded by `macroexpand`, as CL requires.

## `load-time-value` — evaluate once per occurrence

Two mechanisms, one contract:

- **Compile path** — `LispMacroExpander.hoistLoadTimeValues`, invoked from the first
  line of `expandTopLevelDefinitions`. That is the ONE whole-program pass both
  compilers already call (`JvmLispCompiler` / `WasmLispCompiler`), so the hoist needs
  no registration in the CLI, the playground, the corpus tree-shaker tests or the ASDF
  E2E harness — the list a pipeline-level pass would have had to join. Each occurrence
  becomes

  ```lisp
  (defvar %LOAD-TIME-VALUE-1 nil)                       ; prepended at index 0
  (car (or %LOAD-TIME-VALUE-1
           (setq %LOAD-TIME-VALUE-1 (list <value form>))))
  ```

  The slot holds a one-element LIST so a `nil` result still counts as computed. Names
  are numbered from 1 in walk order, so the emitted program stays deterministic
  (`.kb/emitted-output-determinism.md`).
- **Interpreter** — `evalLoadTimeValue` memoizes on the form's cons identity, which is
  CL's own rule for interpreted code and needs no rewrite.

Both memos are capped at 20,000 entries (`EXPANSION_MEMO_LIMIT`): a program that
builds call forms at runtime and feeds them to `eval` would otherwise retain one entry
per form forever. Past the cap the expansion is recomputed — exactly the behavior
before compiler macros existed.

**Why the fill is LAZY rather than at program start**, which is a deliberate deviation
from CL: the value form is routinely spliced out of a library and needs that library's
own globals, which later top-level forms initialize (cl-ppcre's `create-scanner` reads
`*standard-optimize-settings*`, `*allow-quoting*`, `*regex-char-code-limit*`, …).
Evaluating it at index 0 would read them unbound. An occurrence never reached is
therefore never evaluated — the same bargain the macro-time defvar registration makes
(`.kb/defmacro-backquote.md`), and invisible to a value form without side effects,
which is the only kind `load-time-value` is for.

**A top-level `(defvar %LOAD-TIME-VALUE-N nil)` is mandatory, a nested `setq` is not
enough**: both compilers build their global set from `topLevelExprs`, which EXCLUDES
`defun` forms, so a `setq` of an undeclared name inside a function body would get no
backing store.

**Not every occurrence is hoisted, and the exemption is not an optimization.**
`worthHoistingLoadTimeValue` hoists only a CALL. An atom or a variable read (jzon's
`(load-time-value *%offsets*)`) costs nothing to repeat; and `quote` / `function` /
`find-package` wrappers must stay literally where they are, because
`LispMacroExpander.literalPackageDesignator` folds the
`(load-time-value (find-package :ironclad))` idiom into a compile-time package name
and a hoist would hide the literal from it — ironclad's `intern`/`find-symbol` calls
would start building doubly-qualified symbols at runtime.
