# simple-string-p

`(simple-string-p object)`

Returns true when `object` is a SIMPLE string: a string with no fill pointer, not `:adjustable`, and not displaced. A literal, a [`make-string`](make-string.md) result and a [`copy-seq`](copy-seq.md) are simple; a character vector built with `:fill-pointer`/`:adjustable t` and a displaced string view are not. It answers exactly what `(typep object 'simple-string)` does, so the portable "coerce unless `simple-string-p`" idiom copies precisely the strings that need it.

```lisp
(simple-string-p "abc") ; => T
```

```lisp
(simple-string-p (make-array 4 :element-type 'character :fill-pointer 0)) ; => NIL
```

```lisp
(simple-string-p 42) ; => NIL
```
