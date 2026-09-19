# delete-file

`(delete-file string)`

Deletes the file named `string`. A file that does not exist, or cannot be deleted, raises an error `file-error?` answers `#t` for.

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(delete-file "hello.txt")
(file-exists? "hello.txt") ; => #f
(guard (e ((file-error? e) 'no-such-file)) (delete-file "hello.txt")) ; => no-such-file
```
