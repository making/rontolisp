# elt

`(elt sequence index)`

Returns the element at zero-based `index` of `sequence`: a character for a string, the element for a list (the same traversal as `nth` with the arguments swapped) or for a vector. It is also a `setf` place -- see [`setf`](../macros/setf.md) for what each sequence kind does on a write.

```lisp
(elt '(a b c) 1) ; => B
```

```lisp
(elt "abcd" 1) ; => #\b
```

```lisp
(elt (vector 10 20 30) 2) ; => 30
```
