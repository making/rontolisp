# guard

`(guard (variable clause...) body...)`

Evaluates `body` and answers the value of its last expression. If the body raises an object -- through `raise`, `raise-continuable` or `error`, or as an error a built-in procedure signals -- the body is left (the `after` thunks of the `dynamic-wind`s inside it run), `variable` is bound to the object, and the `clause`s are tried as in `cond`, `=>` and `else` included. When no clause is taken, the object is raised again from the `guard`.

Deviations:

- The object is raised again after the body has been left, so a `with-exception-handler` outside the `guard` cannot resume a `raise-continuable` of the body: its handler returning is a secondary error. Gauche resumes the body.
- A body answering several values answers its first one.
- On WebAssembly a string index out of range is not checked: `string-ref` past the end answers a character instead of raising. `error`, `raise`, an arithmetic type error, `car` of a non-pair and a vector index out of range are caught on every backend.
- `eval` refuses `guard` by name.

```scheme
(guard (e ((symbol? e) (list 'caught e))) (raise 'oops)) ; => (caught oops)
(guard (e ((assq 'a e) => cdr) ((assq 'b e))) (raise (list (cons 'a 42)))) ; => 42
(+ 1 (guard (e ((number? e) e)) (raise 5))) ; => 6
(guard (e ((error-object? e) 'caught)) (+ 1 (car '(a)))) ; => caught
```
