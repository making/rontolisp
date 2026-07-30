# coerce

`(coerce object result-type)`

Converts `object` to the given sequence or float type. `result-type` may be `'list`, `'vector`, `'string` (and their `simple-`/`base-` spellings), a compound spec such as `'(vector t)` or `'(string 8)`, a float type (`'float`, `'single-float`, `'double-float`, `'short-float`, `'long-float` -- all the one double representation), or `t` (the identity). A COMPUTED result type is accepted too and dispatches at runtime over exactly those families, so an expression like `(coerce seq type)` with `type` in a variable behaves the same as the literal form. A `'string` result requires a sequence of characters, and a value already of the requested type is returned unchanged. `coerce` is not a first-class function value (`#'coerce` is unavailable), so call it directly.

```lisp
(coerce '(1 2 3) 'vector) ; => #(1 2 3)
(coerce (vector 1 2 3) 'list) ; => (1 2 3)
(coerce "ab" 'list) ; => (#\a #\b)
(coerce '(#\a #\b) 'string) ; => "ab"
```

```lisp
(coerce 1/4 'double-float) ; => 0.25
```

```lisp
(defun convert (seq type) (coerce seq type))
(convert (vector 1 2) 'list) ; => (1 2)
```
