# call-with-values

`(call-with-values producer consumer)`

`producer` を引数なしで呼び出し、それが返した値を引数として `consumer` を呼び出し、`consumer` の値を返します。

```scheme
(call-with-values (lambda () (values 1 2)) cons) ; => (1 . 2)
(call-with-values (lambda () (values 1 2 3)) +) ; => 6
```
