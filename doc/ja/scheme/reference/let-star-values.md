# let*-values

`(let*-values ((formals expression)...) body...)`

`let-values` と同様ですが、節を 1 つずつ順に束縛するため、各 `expression` からはそれより前の節で束縛された変数が見えます。

```scheme
(let*-values (((a b) (values 1 2)) ((c) (values (+ a b)))) (list a b c)) ; => (1 2 3)
```
