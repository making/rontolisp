# coerce

`(coerce object result-type)`

Converts `object` to the given sequence type. `result-type` must be one of the literal types `'list`, `'vector` or `'string` -- a non-literal or any other type is a compile-time/expansion error. A `'string` result requires a sequence of characters, and a value already of the requested type is returned unchanged. `coerce` is not a first-class function value (`#'coerce` is unavailable), so call it directly.

```lisp
(coerce '(1 2 3) 'vector) ; => #(1 2 3)
(coerce (vector 1 2 3) 'list) ; => (1 2 3)
(coerce "ab" 'list) ; => (#\a #\b)
(coerce '(#\a #\b) 'string) ; => "ab"
```
