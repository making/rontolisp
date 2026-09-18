# let-syntax

`(let-syntax ((keyword (syntax-rules ...))...) body...)`

`body` の中で各 `keyword` をマクロに束縛します。`body` は `let` と同じ本体で、その中の定義は局所的です。変換子が見るのは `let-syntax` の外側の束縛で、互いは見えません。

```scheme
(let-syntax ((double (syntax-rules () ((_ e) (* 2 e))))) (double 21)) ; => 42
(let ((x 'outer))
  (let-syntax ((m (syntax-rules () ((_) x))))
    (let ((x 'inner))
      (m)))) ; => outer
```
