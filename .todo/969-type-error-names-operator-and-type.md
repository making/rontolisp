# A type error does not name the operator or the type it wanted

Difficulty: Medium

`(> nil 0)` reports `Unhandled condition: Expected integer, got: NIL`. The text comes from a
generic coercion helper (`Environment.asLong` and its siblings), so it names neither `>` nor
the type `>` actually accepts (REAL) -- "integer" even points a reader at `parse-integer` or
the like. In a program of any size the message alone does not say which call failed.

Goal: every argument-type error names the operator and the expected type, in the shape of a
CL `type-error` report, e.g. `>: The value NIL is not of type REAL`, and the signalled
condition is a `type-error` whose `type-error-datum`/`type-error-expected-type` answer those
values. Identical text on the interpreter, the JVM and both wasm backends (the helpers each
backend inlines or calls must carry the operator name). `ci-spec.yaml` and the doc examples pin
the old text byte for byte; update them with the change.

Read first: `.kb/error-handling.md` (grep `.kb/` for `type-error`).
