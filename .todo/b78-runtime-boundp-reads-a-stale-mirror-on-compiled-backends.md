# b78. Runtime `boundp` of a lambda-assigned global reads a stale mirror on compiled backends

Difficulty: Medium

Found closing b72 (2026-10-02): a runtime `boundp` test inside a namespace init
form is unsound on the compiled backends. It reads the interpreter's `_genv`
mirror, which only assignments made by TOP-LEVEL forms reach -- an assignment
nested in a `(lambda () ...)` (such as an init chunk's `setq`, or any
`defonce`-style guard lowered behind a function boundary) never lands in the
mirror, so the test answers "unbound" forever and a reload always resets the
value. b72 worked around it for init `defonce` with a hoisted `%set` flag plus
`unless` instead of `boundp`.

## Oracle

A Common Lisp `(boundp 'x)` after `(setf x ...)` inside a `labels`/`lambda`
body: decide what the compiled backends may promise (they compile `boundp`
against a static global table; the interpreter reads the live environment).

## Acceptance

- `boundp` of a global assigned only inside a lambda answers the same on all
  four backends (or the divergence is documented in `.kb/` with a pinning test
  naming it).
- The init `defonce` `%set`-flag workaround is re-expressed through the fixed
  primitive if the fix covers it; `clojure-spec.yaml` pins the shape.
