# parameterize

`(parameterize ((parameter value)...) body...)`

Evaluates every `parameter` and `value`, passes each value through its parameter's converter, then evaluates `body` with each parameter answering its converted value, and answers the value of the last expression. The binding is dynamic: a procedure called from `body` sees it too. However `body` is left -- normally, by `raise` or an error, by an escaping continuation, or by `exit` -- each parameter answers its earlier value again. A converter that fails leaves every parameter as it was. `body` may begin with definitions.

Deviations:

- A `parameter` that is not a parameter object is an error. A procedure is called with one argument to ask; anything else is refused by name.
- `eval` refuses `parameterize` by name.

```scheme
(define width (make-parameter 10))
(define (show) (width))
(parameterize ((width 80)) (show)) ; => 80
(show) ; => 10
(guard (e (#t (width))) (parameterize ((width 0)) (raise 'oops))) ; => 10
(let ((p (make-parameter 1))) (parameterize ((p 2)) (p))) ; => 2
```
