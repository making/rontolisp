# 429. An unmatched `signal` inside `handler-case` is fatal on the compiled backends

Difficulty: High

```lisp
(define-condition note () ())
(defun boom () (signal 'note) :returned)

(boom)                                              ; NIL-signalled, then :RETURNED -- correct everywhere
(handler-case (boom) (error (e) :err))              ; CL: :RETURNED
```

| | result |
| --- | --- |
| interpreter | `:RETURNED` -- correct |
| JVM | `Unhandled condition: Condition (NOTE) was signalled.` -- fatal |
| WASM Preview 1 | same, fatal |
| WASM component | same, fatal |

CLHS 9.1.4.1: `signal` runs the applicable handlers and, if none of them
transfers control, **returns nil**. A `handler-case` whose clauses do not match
the condition is not an applicable handler, so it must not see the signal at
all. On the three compiled backends it catches everything, finds no clause, and
turns the decline into a top-level unhandled condition -- the forms after the
`signal` never run.

It is not about user-defined conditions or about a missing parent class; every
shape behaves the same way:

```lisp
(handler-case (progn (signal 'note) :after) (error (e) :err))           ; fatal
(handler-case (progn (signal 'note2) :after) (type-error (e) :te))      ; fatal, note2 :< condition
(handler-case (progn (signal 'simple-warning :format-control "x") :after)
              (error (e) :err))                                         ; fatal, built-in condition
```

Distinct from `.todo/366`, which is `warn`'s own lowering failing to transfer
control INTO a matching clause. This one is the mirror image: a NON-matching
`handler-case` must decline and let the signal return, and the interpreter
already does.

## Why it matters

Found by the cl-mustache spike (`.todo/425`). `read-partial` signals
`partial-cant-be-found` and treats "nobody handled it" as "render the empty
string" -- which is what the mustache spec requires and what the
`handler-bind` + `use-value` extension point is built on. Under a caller's
ordinary `(handler-case (mustache:render* ...) (error (e) ...))` the compiled
backends die on a template that should have rendered.

The general shape is worse than the example: **any** library that uses `signal`
for an optional notification -- a progress hook, a cache miss, a deprecation
notice -- becomes uncompilable the moment a caller anywhere up the stack wraps
the call in a `handler-case` for an unrelated type. The caller's error handling
is what breaks the callee.

## Definition of done

`handler-case` declines a condition no clause matches on all four backends, and
`signal` returns nil so the forms after it run -- with the enclosing
`handler-case` still armed for a LATER condition that does match, and any
intervening `handler-bind` handlers still run at the signal point in the right
order. Check `cerror`'s and `restart-case`'s arms in the same pass. Pinned in
the restart/condition blocks of `LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest` and one `ci-spec.yaml` case, and recorded in
`.kb/error-handling.md` -- whose "Lite deviations" list does not mention it
today.
