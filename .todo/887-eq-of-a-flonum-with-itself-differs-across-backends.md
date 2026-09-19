# `(eq x x)` of a flonum: NIL on the interpreter and the JVM, T on wasm

Difficulty: Medium

Found by `.todo/886` (2026-09-19). The same flonum object read twice from one variable:

```lisp
(defvar *x* (* 1.5d0 (length (list 1 2))))
(print (eq *x* *x*))              ; interpreter NIL, JVM NIL, wasm T, component T; SBCL T
(let ((y *x*)) (print (eq y y)))  ; the same split
```

In Scheme, `(eq? pinf pinf)` for `(define pinf (- (log 0)))` splits the same way
(Gauche `#t`). CLHS leaves `eq` on numbers implementation-dependent, but the backends must
agree (`CLAUDE.md`): pick one answer -- SBCL's T, which boxing identity gives for free on
wasm -- and find where the interpreter and the JVM lose it (a re-box on every read of a
double-valued variable?).

## Test plan

- A `ci-spec.yaml` case on all four backends; the Scheme `eq?` in `scheme-spec.yaml`.
- Measure the JVM cost if the fix is "do not re-box".
