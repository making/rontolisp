# `--dynamic` (late binding) for the JVM/WASM compilers

Opt-in (CLI `--dynamic`; constructor boolean threaded into both `Ctx`). By default an unresolvable call/variable throws `Cannot compile: <name>`; when set, those sites emit a runtime fallback via the embedded `eval` — `(f a b)` -> `_apply(_eval('(function f), null), (list a b))`, `#'f` -> `_eval('(function f), null)`, bare `x` -> `_eval('x, null)`. Arguments compile normally (enclosing locals stay visible); only operator/variable resolution is deferred. Implemented by `Jvm/WasmDynamicCallCompiler`. Forces `usesEval = true`.

Primary use: compile a source that defines functions via `load` without rewriting calls into `(eval ...)`. Tests: `*LispCompilerTest#dynamic*`.
