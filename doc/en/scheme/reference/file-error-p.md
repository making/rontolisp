# file-error?

`(file-error? obj)`

Answers `#t` when `obj` is the error of a failed file operation: a file the [(scheme file)](library-file.md) procedures cannot open, create or delete, or a `file-error` signaled by Common Lisp code the program calls.

```scheme
(guard (e (#t (file-error? e))) (open-input-file "no-such-file.txt")) ; => #t
(guard (e (#t (file-error? e))) (raise 'oops)) ; => #f
```
