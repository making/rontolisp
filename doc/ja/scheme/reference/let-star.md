# let*

`(let* ((variable init)...) body...)`

`let` と同様ですが、変数を 1 つずつ順に束縛するため、各 `init` からはそれより前に束縛された変数が見えます。

```scheme
(let* ((a 1) (b (+ a 1))) (list a b)) ; => (1 2)
(let ((x 1)) (let* ((x (+ x 1)) (y (* x 10))) (list x y))) ; => (2 20)
```
