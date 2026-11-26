# vector-set!

`(vector-set! vector k obj)`

`vector` の添字 `k` に `obj` を格納し、未規定値を返します。ベクタリテラルも変更可能です。R7RS ではリテラルの変更はエラーですが、rontolisp は拒否しません。

```scheme
(let ((v (vector 1 2 3))) (vector-set! v 0 'x) v) ; => #(x 2 3)
(define v (vector 1 2 3))
(vector-set! v 0 'x)
v ; => #(x 2 3)
```
