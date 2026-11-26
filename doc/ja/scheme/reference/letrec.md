# letrec

`(letrec ((variable init)...) body...)`

すべての `init` からすべての変数を参照できるように変数を束縛します。相互再帰する局所手続きはこれで書きます。各 `init` は `lambda` にするか、同じ `letrec` の他の変数の値を使わないようにします。

```scheme
(letrec ((ev? (lambda (n) (if (= n 0) #t (od? (- n 1))))) (od? (lambda (n) (if (= n 0) #f (ev? (- n 1)))))) (ev? 10)) ; => #t
(letrec ((fact (lambda (n) (if (= n 0) 1 (* n (fact (- n 1))))))) (fact 5)) ; => 120
```
