# make-sequence

`(make-sequence result-type size &key initial-element)`

Creates a sequence of the given type and size. A string type (`string`, `simple-string`, `base-string`, `simple-base-string`) builds a string like [`make-string`](make-string.md), `list` builds a list like [`make-list`](make-list.md), and a vector type (`vector`, `simple-vector`) builds an array like [`make-array`](make-array.md). A result type computed at run time, in a call or through `#'make-sequence`, is classified the same way, `bit-vector` and `(vector bit)` included, and any other signals an error; an unsupported literal one is refused when the program compiles. The keyword arguments are forwarded to the underlying constructor (so `:initial-element` follows its support there).

```lisp
(length (make-sequence 'simple-string 5)) ; => 5
```

```lisp
(make-sequence 'list 3) ; => (NIL NIL NIL)
```
