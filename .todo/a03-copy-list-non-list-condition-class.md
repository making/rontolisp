# `copy-list` of a non-list signals a different condition class on every backend

Difficulty: Low

Measured 2026-09-26: the report reads alike everywhere, the condition does not.

```lisp
(defun id (x) x)
(print (handler-case (copy-list (id 5))
         (type-error (e) (list :te (type-error-expected-type e)))
         (error (e) (type-of e))))
```

- interpreter: `(:TE NIL)` -- a `type-error` whose `expected-type` is NIL
- JVM, wasm P1: `SIMPLE-ERROR`

All three report `The value 5 is not of type LIST`, without an operator. The goal is the other
list operators' shape (`.kb/error-handling.md`, "A wrong-type argument names its operator"):
`COPY-LIST: The value 5 is not of type LIST`, a `type-error` with datum 5 and expected type
`LIST` on all four backends, pinned in `ci-spec.yaml`.
