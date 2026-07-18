# make-sequence

`(make-sequence result-type size &key initial-element)`

Creates a sequence of the given type and size. The result type must be a literal quoted specifier: a string type (`string`, `simple-string`, `base-string`, `simple-base-string`) builds a string like [`make-string`](make-string.md), `list` builds a list like [`make-list`](make-list.md), and a vector type (`vector`, `simple-vector`) builds an array like [`make-array`](make-array.md). A non-literal result type is an error, and the keyword arguments are forwarded to the underlying constructor (so `:initial-element` follows its support there).

```lisp
(length (make-sequence 'simple-string 5)) ; => 5
```

```lisp
(make-sequence 'list 3) ; => (nil nil nil)
```
