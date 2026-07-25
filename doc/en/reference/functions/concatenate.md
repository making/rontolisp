# concatenate

`(concatenate result-type &rest sequences)`

Joins its sequence arguments into one new sequence of `result-type`. Three result families are supported: `'string` (also `'simple-string` / `'base-string`), `'list` (also `'cons`), and `'vector` (also `'simple-vector` / `'array` / `'bit-vector`, and compound specs such as `'(vector (unsigned-byte 8))` -- the element type is dropped, since rontolisp vectors are generic). The `'list` and `'vector` families walk elements, so their arguments may be any mix of lists, vectors and strings; the `'string` family joins string arguments only (`coerce` a character list to a string first). With no sequences given it returns the empty sequence of that type, and the result is always fresh -- no argument is shared with it. In the compiled backends `result-type` must be written as a literal quoted designator; the interpreter also accepts a computed one.

```lisp
(concatenate 'string "foo" "bar") ; => "foobar"
(concatenate 'list '(1 2) "ab" #(3)) ; => (1 2 #\a #\b 3)
(concatenate 'vector '(1 2) #(3)) ; => #(1 2 3)
(concatenate '(vector (unsigned-byte 8)) #(1) #(2 3)) ; => #(1 2 3)
```
