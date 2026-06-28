# elt

`(elt list index)`

Returns the element at zero-based `index` of `list`. Unlike Common Lisp's general sequence `elt`, rontolisp's version works on lists only -- it does not index into strings or vectors (use `char` for strings, `aref` for vectors). The traversal is the same as `nth` with the arguments swapped.

```lisp
(elt '(a b c) 1) ; => b
```
