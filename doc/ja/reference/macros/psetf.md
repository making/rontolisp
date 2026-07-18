# psetf

`(psetf place1 e1 place2 e2 ...)`

`psetq` を `setf` プレースへ一般化したものです。すべてのプレースの部分式と右辺の式がまず一時変数へ評価され、その後にはじめてプレースへ代入されます。そのため、先のペアで代入された変数を後のプレースが読んでも、古い値が見えます。`psetf` は常に nil を返します。

```lisp
(let ((a 1) (b 2)) (psetf a b b a) (list a b)) ; => (2 1)
```

```lisp
(let* ((tail (list 2))
       (last-cdr tail)
       (fresh (list 3)))
  (psetf last-cdr fresh
         (cdr last-cdr) fresh)
  tail) ; => (2 3)
```
