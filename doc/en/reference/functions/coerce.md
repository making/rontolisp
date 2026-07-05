# coerce

`(coerce object result-type)`

Converts `object` to the given sequence or float type. `result-type` may be one of the literal types `'list`, `'vector`, `'string`, or a float type (`'float`, `'single-float`, `'double-float`, `'short-float`, `'long-float` -- all the one double representation). A COMPUTED result type is also accepted and dispatches at runtime among the float designators only (any other runtime type signals; the collection conversions need a literal). A `'string` result requires a sequence of characters, and a value already of the requested type is returned unchanged. `coerce` is not a first-class function value (`#'coerce` is unavailable), so call it directly.

```lisp
(coerce '(1 2 3) 'vector) ; => #(1 2 3)
(coerce (vector 1 2 3) 'list) ; => (1 2 3)
(coerce "ab" 'list) ; => (#\a #\b)
(coerce '(#\a #\b) 'string) ; => "ab"
```

```lisp
(coerce 1/4 'double-float) ; => 0.25
```
