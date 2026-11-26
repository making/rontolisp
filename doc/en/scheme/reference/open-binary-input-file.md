# open-binary-input-file

`(open-binary-input-file string)`

Opens the file named `string` and returns a binary input port reading its bytes. A file that cannot be opened raises an error `file-error?` answers `#t` for.

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(define p (open-binary-input-file "hello.txt"))
(list (read-u8 p) (read-bytevector 2 p)) ; => (104 #u8(101 108))
(close-port p)
(delete-file "hello.txt")
```
