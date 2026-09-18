# set!

`(set! variable expression)`

`expression` の値を、既に束縛されている `variable` に格納します。値は未規定値なので REPL は何も表示しません。

```scheme
(let ((x 1)) (set! x 2) x) ; => 2
(define counter 0)
(set! counter (+ counter 1))
counter ; => 1
```
