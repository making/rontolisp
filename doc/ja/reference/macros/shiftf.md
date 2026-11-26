# shiftf

`(shiftf place... new-value)`

[`setf`](setf.md) 可能な各場所の値を左へシフトします。各場所は右隣の場所の古い値を受け取り、最後の場所は `new-value` を受け取り、最初の場所の「古い」値が返されます。すべての場所と新しい値は左から右へ一度だけ評価されます。

```lisp
(let ((a 1) (b 2))
  (list (shiftf a b 9) a b)) ; => (1 2 9)
```
