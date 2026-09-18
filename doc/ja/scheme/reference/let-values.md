# let-values

`(let-values ((formals expression)...) body...)`

多値を返す各 `expression` を評価し、その値を対応する `formals` に束縛してから `body` を評価します。`formals` は `lambda` の仮引数リストと同じ形なので、`(a . rest)` や単一の変数は残りの値をリストとして受け取ります。

```scheme
(let-values (((q r) (values 17 5)) ((s) (values 'x))) (list q r s)) ; => (17 5 x)
(let-values (((a . rest) (values 1 2 3))) (list a rest)) ; => (1 (2 3))
```
