# The uncaught report of a computed condition type without `:format-control` differs per backend

Difficulty: Medium

Found 2026-09-26 while doing a43. An `error` whose condition type is computed and which passes
initargs goes through the shared `%error-runtime` dispatch; each class's helper
(`LispMacroExpander.runtimeErrorDefuns` -> `expandTypedSignal`) builds its signal over
`(getf <initargs> :kw)` reads of EVERY initarg slot, so for a class with a `FORMAT-CONTROL` slot
`suppliedFormatControl` picks `(getf <initargs> :format-control)` as the message -- nil unless the
caller passed one. The condition itself is right everywhere (a `handler-case` on its type catches it
and `~a` prints the same instance); only the uncaught text differs:

```lisp
(defun boom (ty) (error ty :code 42))
(boom 'type-error)
```

| | uncaught report |
|---|---|
| interpreter | `Unhandled condition: Condition (TYPE-ERROR :CODE 42) was signalled.` |
| JVM | `Unhandled condition: NIL` (a43 renders the message with `princ`; before, the cast of the nil message failed and the report was the `NullPointerException`'s text) |
| wasm-GC P1 + component | `Unhandled condition: ` and the trap |

Same with `:datum 1 :expected-type 'string` (interpreter: `Condition (TYPE-ERROR :DATUM 1
:EXPECTED-TYPE STRING) was signalled.`). A literal `(error 'type-error :code 42)` agrees on all four.

- Direction: in the helper, a message from an initarg the caller may not have passed falls back to the
  legacy text over the call's actual initargs -- `(cons '<type> <initargs>)`, which is what the
  interpreter prints -- while an explicit nil `:format-control` on a simple-* class still reports
  `NIL` (the interpreter's text for `(error 'simple-error :format-control nil)`). Read the
  interpreter's rule (`LispEvaluator`'s typed signal) before choosing.
- Pin: a `ci-spec.yaml` case cannot hold an uncaught report; an E2E over the CLI's standard error
  on the four backends, or the report tests that exist for `--report-locations`.
