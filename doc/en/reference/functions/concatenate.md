# concatenate

`(concatenate result-type &rest sequences)`

Joins its sequence arguments into one new sequence of `result-type`. Three result families are supported: `'string` (also `'simple-string` / `'base-string`), `'list` (also `'cons`), and `'vector` (also `'simple-vector` / `'array` / `'bit-vector`, and compound specs such as `'(vector (unsigned-byte 8))`). Every family walks any mix of sequence arguments: the `'string` family too takes any character sequence, `nil` -- the empty list -- included. A `result-type` naming a user `deftype` resolves through its registered expansion to one of the families. With no sequences given it returns the empty sequence of that type, and the result is always fresh -- no argument is shared with it. `result-type` may be computed: a value known only when the call runs reads as the same designator written literally.

A vector `result-type` spelling an `(unsigned-byte 8)`, `(unsigned-byte 16)` or `(unsigned-byte 32)` element type -- `'(vector (unsigned-byte 8))`, `'(simple-array (unsigned-byte 8) (*))` -- builds a specialized vector of that element type, the same representation [`make-array`](make-array.md) produces, so `array-element-type` reports it and `typep` against the matching `simple-array` specifier answers true. Elements are stored masked to the element width. Any other element type builds a general vector, whose element type is `t`.

A vector `result-type` spelling one of the packed float element types -- `'(vector single-float)`, `'(simple-array double-float (*))`, `'(array bfloat16)` -- builds a packed float array of that width, the same representation [`make-array`](make-array.md) produces, so `array-element-type` reports it back. Elements are stored at the array's own width, so an integer element becomes a float and a wider one is narrowed; a non-real element is an error. `bfloat16` arrays exist on the interpreter and the JVM backend only.

A vector `result-type` spelling `character` -- `'(vector character)` -- builds a string instead, the same representation `make-array`'s `:element-type 'character` produces.

```lisp
(concatenate 'string "foo" "bar") ; => "foobar"
(concatenate 'string "a" '(#\b #\c) nil "d") ; => "abcd"
(concatenate 'list '(1 2) "ab" #(3)) ; => (1 2 #\a #\b 3)
(concatenate 'vector '(1 2) #(3)) ; => #(1 2 3)
(concatenate '(vector (unsigned-byte 8)) #(1) #(2 3)) ; => #(1 2 3)
(array-element-type (concatenate '(vector (unsigned-byte 8)) #(1))) ; => (UNSIGNED-BYTE 8)
(array-element-type (concatenate 'vector #(1))) ; => T
(concatenate '(vector single-float) '(1 2) #(3)) ; => #f(1.0 2.0 3.0)
(array-element-type (concatenate '(simple-array double-float (*)) #d(1.0))) ; => DOUBLE-FLOAT
(concatenate '(vector character) "ab" '(#\c)) ; => "abc"
(array-element-type (concatenate '(vector character) "ab")) ; => CHARACTER
(progn (deftype octet-vector () '(simple-array (unsigned-byte 8) (*)))
       (concatenate 'octet-vector #(1) #(2 3))) ; => #(1 2 3)
```
