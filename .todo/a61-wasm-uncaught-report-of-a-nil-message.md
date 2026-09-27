# The wasm-GC uncaught report prints nothing for a nil message; the interpreter and the JVM print `NIL`

Difficulty: Medium

Found 2026-09-27 while doing a50. An uncaught condition whose message is nil at run time:

```lisp
(print (handler-case (error "c") (error (e) :ok)))   ; EH mode on wasm-GC
(error 'simple-error :format-control nil)
```

| | uncaught report |
|---|---|
| interpreter, JVM | `Unhandled condition: NIL` |
| wasm-GC P1 + component | `Unhandled condition: ` |

Same through a computed type (`(defun f (ty) (error ty :format-control nil)) (f 'simple-error)`).
The entry pad (`WasmUncaughtReportCompiler`) guards the text with `(if v v "")` so that a nil never
renders as `NIL` (`.kb/error-handling.md`, "An uncaught condition reports ONE line"), because a
narrowed-away message is also a null cdr there: the cross-lambda-`return-from`-only EH gap and any
site `compileCond` drops the message of. Telling a nil VALUE from an absent message needs the payload
to say which (or the guard removed once every EH-mode site carries its message).

- Pin: a `ci-spec.yaml` `standalone:` case (all four backends), plus the wasm suite's
  `ehUncaughtReportlessConditionReportsTheSignalSiteText` rows.
