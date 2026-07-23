# copy-alist

`(copy-alist alist)`

連想リストのコピーを返します。リストの背骨と各 `(key . value)` ペアセルの両方が新しいconsセルになるため、コピー側のペアを変更(`rplacd` や `cdr` への `setf` など)しても元の連想リストは影響を受けません。キーと値自体は共有され、コピーされません。

```lisp
(copy-alist '((a . 1) (b . 2))) ; => ((a . 1) (b . 2))
```

```lisp
(let* ((orig '((a . 1)))
       (copy (copy-alist orig)))
  (rplacd (assoc 'a copy) 99)
  (cdr (assoc 'a orig))) ; => 1
```
