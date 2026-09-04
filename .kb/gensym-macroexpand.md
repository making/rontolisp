# `gensym` + `macroexpand`/`macroexpand-1`

## `gensym`
- Returns the ordinary symbol `#:<prefix><n>` (no uninterned symbols; default prefix `G`).
- Interpreter: `Environment`, per-environment `AtomicLong`. JVM: `JvmGensymCompiler`, static `_gensymCtr` int field declared unconditionally like `_col`. WASM: `FUNC_GENSYM` slotted before `FUNC_USER_BASE` like the mod/rem helpers; `WasmGensymRuntimeBuilder` bump-allocates prefix bytes + decimal digits at `HEAP_PTR_ADDR`, counter word at `GENSYM_CTR_ADDR`=144.
- The prefix must be a **literal** string on the compile path (like `open`'s `:direction`). A 0-arg `BuiltinFunctionWrappers` entry makes `#'gensym` work.
- `PackageRegistry.splitQualified` exempts `#:`-prefixed names from package qualification; without it `#:G1` errors as package `#` when expanded macro bodies reach `PackageResolver`.
- **Trap: numbering diverges across backends.** The interpreter shares ONE counter between macro-expansion time and run time; the compile path expands macros in a separate macro-time evaluator whose counter is bumped only by what it actually evaluates — and macro-time globals are LAZY (`.kb/defmacro-backquote.md`), so an unread `(defvar *x* (gensym))` no longer advances it. Names renumber but stay bound inside their own expansion. ci-spec `gensym-and-macroexpand` prints only RUNTIME gensyms.

## `macroexpand-1` / `macroexpand`
- Interpreter-native: `LispEvaluator.macroexpand1/macroexpand`, registered in `registerEval` (they need `userMacros`); built-in macros via `LispMacroExpander.expandBuiltinMacro`, whose case list must stay in sync with `PackageRegistry.CL_MACROS`. Top-level operator only.
- `expanded-p` second value: interpreter via the `%mv-spill` channel (`LispEvaluator.expandedWithFlag`), compile paths via the `(values 'expansion expanded-p)` fold.
- No wrapper and no Jvm/Wasm compiler for either: the macro table does not exist at runtime.

**Invariant: whatever `macro-function` says about an operator, `macroexpand-1` must not contradict it.**
- Compile path: `UserMacroExpander` folds a literal `(macroexpand[-1] 'form)` to `(values 'expansion expanded-p)`. Its activation gate also triggers on macroexpand calls with no defmacro present.
- A COMPUTED argument reaches the `LispPreludeLibrary` definition: form unchanged, `expanded-p` nil, UNLESS its operator has a `macro-function` — then it signals "a compiled program cannot expand a macro at run time" (as `macro-function`'s expander stub does).
- **Trap this replaces**: answering a macro call with ITSELF (the old identity defun) makes the standard `(do ((step form (macroexpand-1 step))) ((not (macro-function (first step))) ...))` loop (rove's `form-steps`) spin forever on compiled backends while terminating on the interpreter. The identity half is what lets ironclad's `trivial-macroexpand-all` compile.
- A surviving `macroexpand`/`macroexpand-1` call triggers `UserMacroExpander.emitMacroFunctionTable` (`usesMacroIntrospection`); without the program's own macro names there, a USER macro call is answered with silence where a built-in signals.
- Re-evaluation trigger: once a compiled program carries a runtime macro table, both the stub and this signal become real expansions.

## Tests
`UserMacroExpanderTest` (fold shape); `LispEvaluatorTest#macroexpand1AnswersTheExpandedPFlag`, `#macroexpand1OfAComputedArgumentExpandsOnTheInterpreter`; `JvmLispCompilerTest#compileAndRunMacroexpandOfAComputedArgument`; `WasmLispCompilerIntegrationTest#compileMacroexpandOfAComputedArgument`; ci-spec `gensym-and-macroexpand`, `macro-function-and-special-operator-p`. Predicate mechanics: `.kb/symbol-runtime-api.md`.
