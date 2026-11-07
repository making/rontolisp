# equal

`(equal x y)`

Structural equality: cons cells are compared recursively by their car and cdr, strings character by character, and bit vectors element by element (up to a fill pointer); any other array, a structure and a CLOS instance are equal only to themselves. For everything else it behaves like `eql`. Returns `t` or `nil`. Works on all four backends.

```lisp
(equal '(1 2 (3)) '(1 2 (3))) ; => T
```

```lisp
(equal "abc" "abc") ; => T
```

```lisp
(equal #*101 #*101) ; => T
```

```lisp
(equal #(1 0 1) #(1 0 1)) ; => NIL
```
