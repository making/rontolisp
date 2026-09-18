# assoc

`(assoc obj alist)` `(assoc obj alist compare)`

Like `assq`, but compares with `equal?`, or with the procedure `compare` when one is given, called as `(compare obj key)`.

```scheme
(assoc "b" '(("a" . 1) ("b" . 2))) ; => ("b" . 2)
(assoc (list 'a) '(((a)) ((b)))) ; => ((a))
(assoc 5.0 '((2 3) (5 7)) =) ; => (5 7)
```
