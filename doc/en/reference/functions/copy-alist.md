# copy-alist

`(copy-alist alist)`

Returns a copy of an association list: both the list spine and each `(key . value)` pair cell are fresh cons cells, so mutating a pair in the copy (e.g. with `rplacd` or `setf` of `cdr`) leaves the original alist intact. The keys and values themselves are shared, not copied.

```lisp
(copy-alist '((a . 1) (b . 2))) ; => ((A . 1) (B . 2))
```

```lisp
(let* ((orig (list (cons 'a 1)))
       (copy (copy-alist orig)))
  (rplacd (assoc 'a copy) 99)
  (cdr (assoc 'a orig))) ; => 1
```
