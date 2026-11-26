# set-car!

`(set-car! pair obj)`

`pair` の car に `obj` を格納します。効果のために呼ぶ手続きで、REPL には何も表示されず、値を書き出すと `#!unspecific` になります。

```scheme
(let ((p (list 1 2))) (set-car! p 9) p) ; => (9 2)
(define p (list 1 2))
(set-car! p 9)
p ; => (9 2)
```
