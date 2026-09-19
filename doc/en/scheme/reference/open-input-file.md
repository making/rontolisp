# open-input-file

`(open-input-file string)`

Opens the file named `string` and returns a textual input port reading it. A file that cannot be opened raises an error `file-error?` answers `#t` for. Close the port with `close-port` when done.

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(define p (open-input-file "hello.txt"))
(read-line p) ; => "hello"
(close-port p)
(delete-file "hello.txt")
```
