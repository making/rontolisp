# `gensym` + `macroexpand`/`macroexpand-1`

`gensym` returns the ordinary symbol `#:<prefix><n>` (default prefix `G`). **Invariant: whatever `macro-function` says about an operator, `macroexpand-1` must not contradict it.**

## Where
- `gensym`: `Environment` (per-env `AtomicLong`) / `JvmGensymCompiler` (static `_gensymCtr`) / `FUNC_GENSYM` before `FUNC_USER_BASE` + `WasmGensymRuntimeBuilder`, counter at `GENSYM_CTR_ADDR`=144. Prefix must be a LITERAL string on the compile path; a 0-arg `BuiltinFunctionWrappers` entry makes `#'gensym` work. `PackageRegistry.splitQualified` exempts `#:` names, else `#:G1` errors as package `#`.
- `macroexpand[-1]`: interpreter-native `LispEvaluator.macroexpand1/macroexpand` (`registerEval`); built-ins via `LispMacroExpander.expandBuiltinMacro`, whose case list must stay in sync with `PackageRegistry.CL_MACROS`. Top-level operator only; no wrapper, no Jvm/Wasm compiler.
- `expanded-p`: `%mv-spill` (`LispEvaluator.expandedWithFlag`) / the `(values 'expansion expanded-p)` fold in `UserMacroExpander`. A COMPUTED argument reaches the `LispPreludeLibrary` definition (form unchanged, nil) UNLESS its operator has a `macro-function`, which signals. A surviving call triggers `UserMacroExpander.emitMacroFunctionTable` (`usesMacroIntrospection`).

## Traps
- Gensym numbering diverges across backends: the compile path expands in a separate macro-time evaluator whose globals are LAZY (`.kb/defmacro-backquote.md`). ci-spec prints only RUNTIME gensyms.
- Answering a macro call with ITSELF spins the standard `macroexpand-1` loop (rove's `form-steps`) forever on compiled backends; the identity half is what lets ironclad's `trivial-macroexpand-all` compile.

## Tests
`UserMacroExpanderTest`; `LispEvaluatorTest#macroexpand1AnswersTheExpandedPFlag`, `#macroexpand1OfAComputedArgumentExpandsOnTheInterpreter`; `JvmLispCompilerTest#compileAndRunMacroexpandOfAComputedArgument`; `WasmLispCompilerIntegrationTest#compileMacroexpandOfAComputedArgument`; ci-spec `gensym-and-macroexpand`. Predicates: `.kb/symbol-runtime-api.md`.
