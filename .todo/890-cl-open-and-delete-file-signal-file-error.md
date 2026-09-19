# Common Lisp `open` and `delete-file` signal `file-error`

Difficulty: Medium

Found by `.todo/874` (measured 2026-09-19, all four backends): a failed `open` signals a
SIMPLE-ERROR -- interpreter `OPEN: cannot open file ...` (`Environment`, a plain
`LispEvalException`), JVM the raw `FileNotFoundException` text, wasm `open: cannot open
file` (`WasmOpenCompiler`, `ERROR_INTERNAL`) -- and so does the prelude `delete-file` of a
missing file (`DELETE-FILE: cannot delete ~A`), whose own comments say "a missing file is a
file-error". ANSI requires `file-error`; `(handler-case (open p) (file-error () ..))` misses
it today on every backend.

## Plan

- Interpreter: `LispEvalException.ofClass("FILE-ERROR", ..)` at the `open` throw site.
- JVM: the `_open` failure typed as `file-error` where `JvmHandlerCaseCompiler` synthesizes
  a raw failure's class (a new arm in `LispMacroExpander.rawFailureConditionClasses`, or a
  typed throw from `_open`), and the WASM twin at `WasmOpenCompiler`'s signal.
- `delete-file` / `rename-file` in `LispPreludeLibrary`: `(error 'file-error :pathname ..)`
  with the same message.
- A ci-spec case: `handler-case` on `file-error` around a failed `open` / `delete-file`
  with a run-time path, all four backends.
- Then the Scheme openers' `handler-case` in `scheme.lisp` (`.kb/scheme-frontend.md`, "File
  ports") could narrow to `file-error`; keep it if other errors still reach it.
