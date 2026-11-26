# =>

`(cond (test => receiver)...)` `(case key ((datum...) => receiver)...)`

`cond` や `case` の節で使う補助構文です。その節は式を評価する代わりに、1 引数の手続き `receiver` を test の値（`cond`）または key（`case`）で呼び、その結果を返します。単独では意味を持ちません。

```scheme
(cond ((assv 'b '((a 1) (b 2))) => cadr) (else #f)) ; => 2
(case 2 ((1 2) => (lambda (v) (* v 10))) (else 'no)) ; => 20
```
