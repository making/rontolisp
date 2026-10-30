# `with-simple-restart` and `cerror` reports drop their format arguments

Difficulty: Medium

The shared restart lowering (`LispMacroExpander.expandWithSimpleRestart`,
`expandCerror(cons, registry, true)`) stores the format control as the restart's report
and drops the arguments. `rontolisp --swank`'s debugger now renders reports
(`.kb/swank.md`, "Restarts"), so `(with-simple-restart (skip "Skip ~a." 'it) ...)` lists
`[SKIP] Skip ~a.` where SBCL lists `Skip IT.`, and `(cerror "Use ~s." "bad ~s" x)` lists
`Use ~s.`. The control is shown as written: formatted without its arguments it read
`Skip NIL.` (`.todo/h27-a-run-time-format-control-reads-a-missing-argument-as-nil.md`).

Direction: lower the report to a function over the arguments, as CLHS describes and SBCL
expands: `with-simple-restart`'s `:report (lambda (s) (format s control args...))` (the
arguments evaluated when the report prints); `cerror`'s arguments bound once around the
`restart-case`, so the error and the report see the same values. Only forms with format
arguments change; the restart record keeps its shape. This is the one shared lowering of
`.kb/error-handling.md`, "Phase 4", so it changes on all four backends together: check
the restart tests of `LispEvaluatorTest`, `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest` and ci-spec `restart-system`, then the debugger listing
in `cli/SwankServerTest`.
