# set-cdr!

`(set-cdr! pair obj)`

`pair` の cdr に `obj` を格納します。`set-car!` と同様に効果のために呼ぶ手続きです。

```scheme
(let ((p (list 1 2))) (set-cdr! p '(3 4)) p) ; => (1 3 4)
(define p (list 1 2))
(set-cdr! p '(3 4))
p ; => (1 3 4)
```
