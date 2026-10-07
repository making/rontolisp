# e01. make-list of a bignum length runs without end on the compiled backends

Difficulty: Medium

Found while closing the bignum `make-array` dimension item. Measured 2026-10-07, the length
from a variable, under `handler-case`:

```lisp
(defvar *big* (expt 2 100))
(print (handler-case (length (make-list *big*)) (error (e) (princ-to-string e))))
```

| SBCL 2.2.9 | interpreter | JVM | P1 |
|---|---|---|---|
| `TYPE-ERROR`, `(UNSIGNED-BYTE 58)` | unnamed `The value ... is not of type INTEGER` | no answer in 30 s (`-Xmx512m`) | no answer in 20 s |

A negative length was not measured.

Plan: a length that is no integer in a backend-independent range signals one named
`type-error` on all four backends, as `make-array`'s dimension does
(`.kb/error-handling.md`, "A make-array dimension"); pin it in `ci-spec.yaml` and the
evaluator / JVM / wasm triple.
