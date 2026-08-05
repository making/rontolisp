# concatenate

`(concatenate result-type &rest sequences)`

Joins its sequence arguments into one new sequence of `result-type`. Three result families are supported: `'string` (also `'simple-string` / `'base-string`), `'list` (also `'cons`), and `'vector` (also `'simple-vector` / `'array` / `'bit-vector`, and compound specs such as `'(vector (unsigned-byte 8))`). Every family walks any mix of sequence arguments: the `'string` family too takes any character sequence, `nil` -- the empty list -- included. A `result-type` naming a user `deftype` resolves through its registered expansion to one of the families. With no sequences given it returns the empty sequence of that type, and the result is always fresh -- no argument is shared with it. In the compiled backends `result-type` must be written as a literal quoted designator; the interpreter also accepts a computed one.

A vector `result-type` spelling an `(unsigned-byte 8)`, `(unsigned-byte 16)` or `(unsigned-byte 32)` element type -- `'(vector (unsigned-byte 8))`, `'(simple-array (unsigned-byte 8) (*))` -- builds a specialized vector of that element type, the same representation [`make-array`](make-array.md) produces, so `array-element-type` reports it and `typep` against the matching `simple-array` specifier answers true. Elements are stored masked to the element width. Any other element type builds a general vector, whose element type is `t`.

```lisp
(concatenate 'string "foo" "bar") ; => "foobar"
(concatenate 'string "a" '(#\b #\c) nil "d") ; => "abcd"
(concatenate 'list '(1 2) "ab" #(3)) ; => (1 2 #\a #\b 3)
(concatenate 'vector '(1 2) #(3)) ; => #(1 2 3)
(concatenate '(vector (unsigned-byte 8)) #(1) #(2 3)) ; => #(1 2 3)
(array-element-type (concatenate '(vector (unsigned-byte 8)) #(1))) ; => (UNSIGNED-BYTE 8)
(array-element-type (concatenate 'vector #(1))) ; => T
(progn (deftype octet-vector () '(simple-array (unsigned-byte 8) (*)))
       (concatenate 'octet-vector #(1) #(2 3))) ; => #(1 2 3)
```
