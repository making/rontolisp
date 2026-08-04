# concatenate

`(concatenate result-type &rest sequences)`

Joins its sequence arguments into one new sequence of `result-type`. Three result families are supported: `'string` (also `'simple-string` / `'base-string`), `'list` (also `'cons`), and `'vector` (also `'simple-vector` / `'array` / `'bit-vector`, and compound specs such as `'(vector (unsigned-byte 8))` -- the element type is dropped, since rontolisp vectors are generic). Every family walks any mix of sequence arguments: the `'string` family too takes any character sequence, `nil` -- the empty list -- included. A `result-type` naming a user `deftype` resolves through its registered expansion to one of the families. With no sequences given it returns the empty sequence of that type, and the result is always fresh -- no argument is shared with it. In the compiled backends `result-type` must be written as a literal quoted designator; the interpreter also accepts a computed one.

```lisp
(concatenate 'string "foo" "bar") ; => "foobar"
(concatenate 'string "a" '(#\b #\c) nil "d") ; => "abcd"
(concatenate 'list '(1 2) "ab" #(3)) ; => (1 2 #\a #\b 3)
(concatenate 'vector '(1 2) #(3)) ; => #(1 2 3)
(concatenate '(vector (unsigned-byte 8)) #(1) #(2 3)) ; => #(1 2 3)
(progn (deftype octet-vector () '(simple-array (unsigned-byte 8) (*)))
       (concatenate 'octet-vector #(1) #(2 3))) ; => #(1 2 3)
```
