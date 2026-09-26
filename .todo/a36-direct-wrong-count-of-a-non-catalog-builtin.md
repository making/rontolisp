# A direct wrong-count call of a built-in outside the wrapper catalog

Difficulty: Medium

`BuiltinCallArity` (catalog built-ins) and `DefinedCallArity` (the program's own defuns) turn a
direct call with a wrong count into the interpreter's run-time `program-error`. A built-in that is
neither -- lowered by its own per-operator compiler, or implemented by a spliced library defun --
still diverges:

| form | interpreter | JVM | wasm `--component` (2026-09-26) |
|---|---|---|---|
| `(rontolisp:tcp-connect "127.0.0.1")` | `TCP-CONNECT expects 2 arguments, got 1` | compile error (`JvmTcpCompiler`) | `Function expects 2 arguments, got 1` |
| `(rontolisp:tcp-listen)` | `TCP-LISTEN expects 1 or 2 arguments, got 0` | compile error | `Function expects at least 1 argument, got 0` |

- JVM / wasm: the per-operator lowerings still throw at compile time -- `JvmTcpCompiler`,
  `JvmMathFnCompiler`, `JvmComplexCompiler`, `JvmAsyncOpsCompiler`, `JvmQuantizedMatrixCompiler`,
  `JvmSymbolApiCompiler`, `JvmFileMetaCompiler`, `JvmSimdCompiler`, the `rontolisp:` chain in
  `JvmExprCompiler`; `WasmMutexCompiler`, `WasmVecSimdCompiler`, `WasmFutureInternalCompiler`,
  `WasmStrByteCompiler` (grep `expects " +` under `codegen/`). Which of them the catalog check
  already shadows is not measured.
- A spliced library defun (sockets.lisp on the component) reports `Function`, on the direct and
  the function-value path, where the interpreter's Java built-in names itself.

Goal: every direct wrong-count call is the interpreter's run-time report, operator name included,
on all four backends; pin in `ci-spec.yaml` next to `direct-defun-call-wrong-count-signals-program-error`.
