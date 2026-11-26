# raise

`(raise obj)`

Raises `obj`, which may be any object: the innermost handler installed by `with-exception-handler` is called with it, or the innermost `guard` takes it. A handler that returns makes a secondary error, raised where the handler ran. With no handler at all the program ends, reporting `obj` as `write` shows it (`(raise (list 1 "a"))` reports `(1 "a")`) and exiting with status 1.

```scheme
(guard (e (#t (list 'caught e))) (raise 42)) ; => (caught 42)
(guard (e ((string? e) (string-append "got " e))) (raise "it")) ; => "got it"
```
