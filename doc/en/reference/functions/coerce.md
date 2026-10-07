# coerce

`(coerce object result-type)`

Converts `object` to the given sequence, float, complex, real, or function type. `result-type` may be `'list`, `'vector`, `'string` (and their `simple-`/`base-` spellings), a compound spec such as `'(vector t)` or `'(string 8)`, a float type (`'float`, `'single-float`, `'double-float`, `'short-float`, `'long-float` -- all the one double representation), `'complex` (a complex answers itself; a real answers [`complex`](complex.md) of itself, demoted when exact, so `(coerce 5 'complex)` is `5` and `(coerce 5.0 'complex)` is `#C(5.0 0.0)`), `'(complex part-type)` (both parts are coerced first, so `(coerce 5 '(complex single-float))` is `#C(5.0 0.0)`), `'real` (a real answers itself; anything else signals a catchable type error), `'function` (a function is returned as is, a symbol resolves through [`symbol-function`](symbol-function.md), and a literal lambda list becomes the closure), or `t` (the identity). A COMPUTED result type is accepted too and dispatches at runtime over exactly those families, so an expression like `(coerce seq type)` with `type` in a variable behaves the same as the literal form; a zero-parameter [`deftype`](../macros/deftype.md) name resolves to what it expands to first. A `'string` result requires a sequence of characters, and a value already of the requested type is returned unchanged -- for a `'simple-string` result that means a SIMPLE string only, so a fill-pointered or adjustable character vector is rebuilt rather than handed back. `#'coerce` is a function value too, with the same computed-type dispatch.

A vector `result-type` spelling an `(unsigned-byte 8)`, `(unsigned-byte 16)` or `(unsigned-byte 32)` element type -- `'(vector (unsigned-byte 8))`, `'(simple-array (unsigned-byte 32) (*))` -- builds a specialized vector of that element type, the same representation [`make-array`](make-array.md) and [`concatenate`](concatenate.md) produce, so `array-element-type` reports it and `typep` against the matching `simple-array` specifier answers true. Elements are stored masked to the element width. Any other element type builds a general vector, whose element type is `t`. This is the spelling a lookup table usually takes, and a table whose elements are all literals is built at compile time on the compiled backends.

A vector `result-type` spelling one of the packed float element types -- `'(vector single-float)`, `'(simple-array double-float (*))`, `'(array bfloat16)` -- builds a packed float array of that width, the same representation [`make-array`](make-array.md) produces, so `array-element-type` reports it back. Elements are stored at the array's own width, so an integer element becomes a float and a wider one is narrowed; a non-real element is an error. `bfloat16` arrays exist on the interpreter and the JVM backend only.

A vector `result-type` spelling `character` -- `'(vector character)` -- builds a string instead, the same representation `make-array`'s `:element-type 'character` produces: `array-element-type` answers `character`, `stringp` is true, and the printed form is a normal string.

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
(coerce '(1 2) '(vector single-float)) ; => #f(1.0 2.0)
(array-element-type (coerce '(1.0) '(simple-array double-float (*)))) ; => DOUBLE-FLOAT
```

```lisp
(coerce '(#\a #\b) '(vector character)) ; => "ab"
(array-element-type (coerce '(#\a #\b) '(vector character))) ; => CHARACTER
```

```lisp
(coerce 1/4 'double-float) ; => 0.25
```

```lisp
(coerce 5 'complex) ; => 5
```

```lisp
(coerce 5.0 'complex) ; => #C(5.0 0.0)
```

```lisp
(coerce 5 '(complex single-float)) ; => #C(5.0 0.0)
```

```lisp
(funcall (coerce 'car 'function) '(1 2 3)) ; => 1
```

```lisp
(defun convert (seq type) (coerce seq type))
(convert (vector 1 2) 'list) ; => (1 2)
```
