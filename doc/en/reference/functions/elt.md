# elt

`(elt sequence index)`

Returns the element at zero-based `index` of `sequence`: a character for a string, the element for a list or for a vector. An `index` outside the sequence -- negative, or not less than its length -- signals a `type-error` whose expected type is `(integer 0 (length))`; unlike [`nth`](nth.md), reading past a list's end is not `nil`. It is also a `setf` place -- see [`setf`](../macros/setf.md) for what each sequence kind does on a write.

```lisp
(elt '(a b c) 1) ; => B
```

```lisp
(elt "abcd" 1) ; => #\b
```

```lisp
(elt (vector 10 20 30) 2) ; => 30
```

```lisp
(handler-case (elt '(a b c) 3) (type-error (e) (type-error-expected-type e))) ; => (INTEGER 0 (3))
```
