# acons

`(acons key datum alist)`

Returns a new alist with the pair `(key . datum)` prepended to `alist`. It is shorthand for `(cons (cons key datum) alist)` and does not modify the original list. The fresh pair shadows any earlier entry for the same key in `assoc` lookups.

```lisp
(acons 'b 2 (list (cons 'a 1))) ; => ((b . 2) (a . 1))
```
