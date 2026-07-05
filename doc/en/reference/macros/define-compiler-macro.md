# define-compiler-macro

`(define-compiler-macro name lambda-list body...)`

Accepted as a parsed no-op returning `nil`, like [`declaim`](declaim.md) and [`deftype`](deftype.md). A compiler macro is only an optimization hint, so dropping it is behavior-preserving: the ordinary function definition of `name` stays authoritative (the same result, only without the hand-written optimization). The `&whole` parameter and the body are ignored.

```lisp
(defun myinc (x) (+ x 1))
(define-compiler-macro myinc (x) `(+ ,x 100)) ; ignored
(myinc 10) ; => 11
```
