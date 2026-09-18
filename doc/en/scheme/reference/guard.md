# guard

`(guard (variable clause...) body...)`

Evaluates `body` and answers the value of its last expression. If the body raises an object -- through `raise`, `raise-continuable` or `error`, or as an error a built-in procedure signals -- the body is left (the `after` thunks of the `dynamic-wind`s inside it run), `variable` is bound to the object, and the `clause`s are tried as in `cond`, `=>` and `else` included. When no clause is taken, the object is raised again from the `guard`.

Deviations:

- The object is raised again after the body has been left, so a `with-exception-handler` outside the `guard` cannot resume a `raise-continuable` of the body: its handler returning is a secondary error. Gauche resumes the body.
- A body answering several values answers its first one.
- On WebAssembly, an error the machine traps on -- `car` of a non-pair, a vector index out of range -- ends the program instead of being raised. `error`, `raise` and an arithmetic type error are caught on every backend.
- `eval` refuses `guard` by name.

```scheme
(guard (e ((symbol? e) (list 'caught e))) (raise 'oops)) ; => (caught oops)
(guard (e ((assq 'a e) => cdr) ((assq 'b e))) (raise (list (cons 'a 42)))) ; => 42
(+ 1 (guard (e ((number? e) e)) (raise 5))) ; => 6
(guard (e ((error-object? e) 'caught)) (+ 1 (car '(a)))) ; => caught
```
