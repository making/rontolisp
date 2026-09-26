# A runtime error does not say where it happened

Difficulty: High

A reader or compile error is prefixed `file:line:column:`, but an unhandled RUNTIME condition
prints only its report text, on every backend. `.kb/error-handling.md` ("A RUNTIME condition
carries no such prefix ... deliberately") and `.kb/source-positions.md` ("The compile-path-only
divergence") record why: recording positions in the interpreter would change runtime error
text that `ci-spec.yaml` and the doc examples pin byte for byte. That is a test constraint
driving a user-facing decision; the tests should adapt instead.

It is worst inside async code: an error in an `async-defun` body is raised on a virtual thread
and rethrown by `await`, so it appears to come from the top-level form that awaited, and
`RONTOLISP_DEBUG=1` shows only the virtual thread's Java stack -- neither the Lisp function nor
the await site.

Goal: the uncaught-condition report names where it happened -- the innermost user form's
`file:line` and the enclosing function, plus for an async body the await site -- on the
interpreter, the JVM and both wasm backends, with compiled output that stays byte-identical
for a program that never signals. A shape to start from: keep the condition's report text
unchanged and add a location line under it, e.g.

```
Unhandled condition: ...
  in CURRENT-PRICE (async), awaited at app.lisp:79
  at app.lisp:33
```

so `format nil "~a"` of a condition, `handler-case` and anything a program reads stay as they
are; only the top-level report grows. The pinned outputs then either expect the location line
or the harness compares the report line alone -- decide which, per suite, and state it in the
`.kb` files.

Open points to measure, not assume: the interpreter's cost of carrying a position per form
(record only on the throw path: a frame pushed as the exception passes a user-function
application costs nothing when nothing throws); where the JVM gets a line (`LineNumberTable`
from the compile-path position table) and a wasm module gets one (a function-index -> name and
offset -> line table the runner or host reads, or a name section); the served-request `load`
concern that made recording thread-local.

Read first: `.kb/source-positions.md`, `.kb/error-handling.md`; retire or rewrite the
"deliberately" paragraphs there with the change.
