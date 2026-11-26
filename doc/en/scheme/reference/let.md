# let

`(let ((variable init)...) body...)` `(let name ((variable init)...) body...)`

Evaluates every `init`, binds each `variable` to its value, and evaluates `body`. The second form, named `let`, also binds `name` to a procedure of the variables whose body is `body`, for loops. A named `let` whose name is only called in tail position runs in constant stack.

```scheme
(let ((a 1) (b 2)) (+ a b)) ; => 3
(let loop ((i 0) (acc '())) (if (= i 3) acc (loop (+ i 1) (cons i acc)))) ; => (2 1 0)
```
