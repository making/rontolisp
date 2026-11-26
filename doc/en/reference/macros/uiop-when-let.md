# uiop:when-let

`(uiop:when-let ((var form)...) body...)`

[`uiop:if-let`](uiop-if-let.md) with an implicit `progn` body and no else
branch: binds the variables in parallel, and evaluates the body only when
**every** variable came out non-nil. Returns nil otherwise.

The single un-nested binding spelling — `(uiop:when-let (x form) ...)` — is
accepted here too.

```lisp
(list (uiop:when-let ((a 3) (b 4)) (+ a b) (* a b))
      (uiop:when-let ((a 3) (b nil)) (+ a 1)))   ; => (12 NIL)
```

`uiop` is ASDF's portability layer, not part of Common Lisp: the name is only
reachable with the `uiop:` qualifier.

## Backend support

Works on all four backends: it is a built-in macro expansion shared by the
interpreter and both compilers. Like the other built-in macros it has no
function value (`#'uiop:when-let` is an error).
