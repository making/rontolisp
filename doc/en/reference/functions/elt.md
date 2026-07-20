# elt

`(elt sequence index)`

Returns the element at zero-based `index` of `sequence`: a character for a string, the element for a list (the same traversal as `nth` with the arguments swapped). Unlike Common Lisp's general sequence `elt`, rontolisp's version does not index into vectors (use `aref` for vectors).

```lisp
(elt '(a b c) 1) ; => B
```

```lisp
(elt "abcd" 1) ; => #\b
```
