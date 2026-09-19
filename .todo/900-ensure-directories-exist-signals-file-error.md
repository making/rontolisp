# `ensure-directories-exist` signals `file-error` when it cannot create a directory

Difficulty: Low

Found by `.todo/890` (measured 2026-09-19). With a run-time path the host refuses
(`"/proc/no-such-890/x/y.txt"`):

- SBCL: `file-error`.
- Interpreter, WASM P1, `--component`: a SIMPLE-ERROR (`%make-directories: cannot create
  ...`, `Environment`; `%make-directories: cannot create directory`,
  `WasmMakeDirectoriesCompiler`).
- **JVM: no error at all** -- it answers the path as if the directories were created
  (`JvmIoRuntimeBuilder`'s `%make-directories` ignores the `File.mkdirs` result).

## Plan

- Failing test first (all four; `LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest`, a `ci-spec.yaml` case), `file-error-pathname`
  answering the designator.
- JVM: answer the failure the other backends detect (re-check `isDirectory` after
  `mkdirs`, the WASM verify-by-opening precedent in `.kb/read-load-streams.md`).
- Signal through `%file-error` (`LispMacroExpander.lowerFileError`, `.kb/error-handling.md`)
  on every backend: interpreter via `ClosRegistry.newFileErrorCondition`, WASM by replacing
  `WasmMakeDirectoriesCompiler`'s `%error` with a `%file-error` form (and adding
  `%make-directories` / `ensure-directories-exist` to `LispMacroExpander.FILE_ERROR_SITES`).
