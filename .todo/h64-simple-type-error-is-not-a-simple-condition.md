# h64. `simple-type-error` is not a subtype of `simple-condition`

Difficulty: Low

Found 2026-10-31 while working h38 (`check-type` now signals a `simple-type-error`).
CLHS: `simple-type-error` has class precedence list
`simple-type-error, simple-condition, type-error, error, serious-condition, condition, t`.

```lisp
(typep (make-condition 'simple-type-error :format-control "x") 'simple-condition)
;; CLHS: T   here: NIL
(handler-case (check-type x integer) (simple-condition (c) (simple-condition-format-control c)))
;; CLHS: caught   here: not caught by this clause
```

Give the seeded class `simple-condition` as a second ancestor (the lite-multiple-parents
rule `reader-error` uses, `.kb/error-handling.md`), so `typep`, `handler-case`/`handler-bind`
dispatch and `simple-condition-format-control`/`-arguments` agree, on all four backends.
Extend the ci-spec case `declarations-eval-when-check-type-assert`.
