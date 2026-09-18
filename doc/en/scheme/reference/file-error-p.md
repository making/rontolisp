# file-error?

`(file-error? obj)`

Answers `#t` when `obj` is the error of a failed file operation. The Scheme front end has no procedure that opens a file, so only an error signaled by Common Lisp code the program calls can answer `#t`.

```scheme
(guard (e (#t (file-error? e))) (raise 'oops)) ; => #f
```
