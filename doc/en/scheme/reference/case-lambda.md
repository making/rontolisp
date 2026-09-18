# case-lambda

`(case-lambda (formals body...)...)`

Answers a procedure. A call takes the first clause whose `formals` accept the number of arguments -- `(x y)` exactly two, `(x . rest)` one or more, `args` any number -- binds them as a `lambda` with those formals would, and evaluates its `body`. Each `body` may begin with definitions. A procedure defined by `case-lambda` that calls itself in tail position runs in constant space, as one defined by `lambda` does.

Deviations:

- A call no clause accepts raises an error object whose message is `wrong number of arguments to case-lambda:` followed by the arguments; Gauche spells it `case lambda`.
- `eval` refuses `case-lambda` by name.

```scheme
((case-lambda ((x) (list x)) ((x y) (+ x y))) 1 2) ; => 3
(define range
  (case-lambda
    ((end) (range 0 end))
    ((start end) (if (>= start end) '() (cons start (range (+ start 1) end))))))
(range 3) ; => (0 1 2)
(range 2 5) ; => (2 3 4)
(define f (case-lambda ((x) 'one) ((x . rest) (length rest)) (all 'none)))
(list (f) (f 1) (f 1 2 3)) ; => (none one 2)
(guard (e ((error-object? e) (error-object-message e))) ((case-lambda ((x) x)) 1 2)) ; => "wrong number of arguments to case-lambda: (1 2)"
```
