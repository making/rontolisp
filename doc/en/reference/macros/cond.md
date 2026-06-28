# cond

`(cond (test body...)...)`

Evaluates each clause's `test` in order and, for the first one that is truthy (non-nil), evaluates that clause's body forms and returns the value of the last. A clause with no body returns the value of its own test. If no test is truthy `cond` returns nil; a final `(t body...)` clause acts as a catch-all default. It expands into nested `if` forms.

```lisp
(let ((x 5)) (cond ((< x 0) 'neg) ((= x 0) 'zero) (t 'pos))) ; => pos
```
