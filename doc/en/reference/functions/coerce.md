# coerce

`(coerce object result-type)`

Converts `object` to the given sequence or float type. `result-type` may be `'list`, `'vector`, `'string` (and their `simple-`/`base-` spellings), a compound spec such as `'(vector t)` or `'(string 8)`, a float type (`'float`, `'single-float`, `'double-float`, `'short-float`, `'long-float` -- all the one double representation), or `t` (the identity). A COMPUTED result type is accepted too and dispatches at runtime over exactly those families, so an expression like `(coerce seq type)` with `type` in a variable behaves the same as the literal form; a zero-parameter [`deftype`](../macros/deftype.md) name resolves to what it expands to first. A `'string` result requires a sequence of characters, and a value already of the requested type is returned unchanged -- for a `'simple-string` result that means a SIMPLE string only, so a fill-pointered or adjustable character vector is rebuilt rather than handed back. `coerce` is not a first-class function value (`#'coerce` is unavailable), so call it directly.

A vector `result-type` spelling an `(unsigned-byte 8)`, `(unsigned-byte 16)` or `(unsigned-byte 32)` element type -- `'(vector (unsigned-byte 8))`, `'(simple-array (unsigned-byte 32) (*))` -- builds a specialized vector of that element type, the same representation [`make-array`](make-array.md) and [`concatenate`](concatenate.md) produce, so `array-element-type` reports it and `typep` against the matching `simple-array` specifier answers true. Elements are stored masked to the element width. Any other element type builds a general vector, whose element type is `t`. This is the spelling a lookup table usually takes, and a table whose elements are all literals is built at compile time on the compiled backends.

```lisp
(coerce '(1 2 3) 'vector) ; => #(1 2 3)
(coerce (vector 1 2 3) 'list) ; => (1 2 3)
(coerce "ab" 'list) ; => (#\a #\b)
(coerce '(#\a #\b) 'string) ; => "ab"
```

```lisp
(coerce '(1 2 260) '(vector (unsigned-byte 8))) ; => #(1 2 4)
(array-element-type (coerce '(1) '(simple-array (unsigned-byte 32) (*)))) ; => (UNSIGNED-BYTE 32)
```

```lisp
(coerce 1/4 'double-float) ; => 0.25
```

```lisp
(defun convert (seq type) (coerce seq type))
(convert (vector 1 2) 'list) ; => (1 2)
```
