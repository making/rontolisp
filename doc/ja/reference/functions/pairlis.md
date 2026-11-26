# pairlis

`(pairlis keys data &optional alist)`

キーのリストと値のリストを組にして連想リストを作ります。キーの順序は保存され、省略可能な既存の `alist` は末尾に連結されます。組作りは短い方のリストの終わりで止まります。

```lisp
(pairlis '(a b) '(1 2)) ; => ((A . 1) (B . 2))
```

```lisp
(pairlis '(a b) '(1 2) '((c . 3))) ; => ((A . 1) (B . 2) (C . 3))
```
