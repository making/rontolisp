# make-parameter

`(make-parameter value)`, `(make-parameter value converter)`

Answers a parameter object: a procedure of no arguments answering its current value. That value is `value`, or `(converter value)` when a converter is given, until a [`parameterize`](parameterize.md) binds another. `procedure?` of a parameter object is `#t`. A thread started by `parallel-execute` starts with the caller's values.

Deviation: calling a parameter object with an argument is an error; Gauche sets its value.

```scheme
((make-parameter 5 (lambda (x) (* x 2)))) ; => 10
(define radix (make-parameter 10))
(radix) ; => 10
(parameterize ((radix 2)) (radix)) ; => 2
```
