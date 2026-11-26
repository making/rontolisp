# define-modify-macro

`(define-modify-macro name (parameter...) function [documentation])`

Defines a macro `name` so that `(name place argument...)` expands to `(setf place (function place argument...))` -- a read-modify-write of `place` through `function`. A trailing `&rest` parameter is spliced into the call. Lite: the `place` subforms may be evaluated more than once (no `get-setf-expansion` single-evaluation protocol) and the optional documentation string is ignored.

```lisp
(define-modify-macro maxf (lo) max) ; => MAXF
```
