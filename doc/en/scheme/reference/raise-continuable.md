# raise-continuable

`(raise-continuable obj)`

Raises `obj` like `raise`, except that the innermost handler's value becomes the value of the `raise-continuable` call, and the program continues from there. Caught by a `guard`, it behaves as `raise`.

```scheme
(with-exception-handler (lambda (e) 10) (lambda () (+ 1 (raise-continuable 'oops)))) ; => 11
(with-exception-handler (lambda (e) (* e 2)) (lambda () (list (raise-continuable 1) (raise-continuable 2)))) ; => (2 4)
```
