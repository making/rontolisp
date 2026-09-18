# begin

`(begin expression...)`

式を左から右へ評価し、最後の式の値を返します。`(begin)` は未規定値を返します。ファイルや REPL のトップレベルでは `begin` はその中の形式をトップレベルへ展開するため、定義を含められます。REPL はその各形式の値を個別に表示します。

```scheme
(let ((x 1)) (begin (set! x (* x 10)) (+ x 1))) ; => 11
(list (begin)) ; => (#!unspecific)
```
