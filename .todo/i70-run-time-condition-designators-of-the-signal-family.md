# i70. Run-time condition designators of the signal family

Difficulty: Medium

Measured 2026-11-05 against SBCL 2.2.9, where `id` is `(defun id (x) x)` and `my-e`/`my-c`/`my-w` are an `error`, a `condition`
and a `warning` subclass.

| Form | SBCL | Interpreter | JVM, Preview 1, component |
| --- | --- | --- | --- |
| `(error (id 'my-e))` | `my-e` | `my-e` | `simple-error` naming it |
| `(funcall #'error 'my-e)` | `my-e` | `my-e` | `simple-error` naming it |
| `(signal (id 'my-c))` | `my-c` | `simple-condition` naming it | `simple-condition` naming it |
| `(funcall #'signal 'my-c)` | `my-c` | `my-c` | `simple-condition` naming it |
| `(warn (id 'my-w))` | `my-w` | `simple-warning` naming it | `simple-warning` naming it |
| `(warn (id 'simple-error))` | `type-error` | `simple-warning` naming it | `simple-warning` naming it |
| `(funcall #'warn 'simple-error)`, `(eval '(warn 'simple-error))` | `type-error` | `type-error` | `simple-warning` naming it |
| `(warn (id nil))`, `(warn t)`, `(warn :k)` | `type-error` | `simple-warning` naming it | `simple-warning` naming it |
| `(error 'undefined-thing)`, `(warn 'undefined-thing)` | `type-error` | `simple-condition` naming it | `simple-condition` naming it |
| `(error (id 5))` | `type-error` | no `error` clause takes it: `Unhandled condition: 5` | the same |
| `(signal (id 5))` | `type-error` | nil | nil |

`doc/en/reference/macros/error.md` documents the compiled `#'error` shape, so the table is a known limit, not a regression.

## Plan

A symbol's class is only known to a per-class dispatch, and `%error-runtime` builds one for a call WITH initargs
(`.kb/error-handling.md`, "cerror, signal-operator function values, runtime type dispatch"). A datum-only call stayed off it
because a slot-less instance runs its `:report` over nil slots; SBCL's report prints those as unbound only when printed, so that
reason wants re-measuring. The dispatch must not reach a program that only computes control strings: restrict it to the classes
whose names the program spells as symbols, the same over-approximation `usedLayoutTags` makes. `(error 5)` needs a test in
`expandObjectSignal`'s condition arm, out of line (`%struct-type-error` is the shape): a computed `(error (format nil ...))` is
common enough that an inline `type-error` per site costs bytes. Keep the `warn` object arm's test (`.kb/error-handling.md`, "`warn`
of what is no warning is a `type-error`") and drop the symbol arm once this reaches it. Undefined names need a decision: the
degradation exists for a library's cold branch naming a sibling package's condition unqualified (`buildTypedConstruct`).
