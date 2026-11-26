# letrec-syntax

`(letrec-syntax ((keyword (syntax-rules ...))...) body...)`

`let-syntax` と同じですが、変換子は束縛されるすべての `keyword` を見るので、マクロは自分自身や互いを使えます。

```scheme
(letrec-syntax ((my-or (syntax-rules () ((_) #f) ((_ e r ...) (let ((t e)) (if t t (my-or r ...))))))) (my-or #f 7)) ; => 7
```
