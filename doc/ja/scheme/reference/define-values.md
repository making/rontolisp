# define-values

`(define-values (variable...) expression)`

`expression` を評価し（変数と同じ数の値を返す必要があります）、各変数を対応する値に定義します。トップレベルにも内部定義にも書けます。残余仮引数（`(define-values (a . rest) ...)` や `(define-values all ...)`）には対応していません。

```scheme
(let () (define-values (q r) (values 17 5)) (list q r)) ; => (17 5)
(define-values (a b) (values 1 2))
(+ a b) ; => 3
```
