# call-with-input-file

`(call-with-input-file string proc)`

Opens the file named `string` as by `open-input-file`, calls `proc` with the port, closes the port when `proc` returns, and returns what `proc` returned. If `proc` does not return, the port stays open.

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(call-with-input-file "hello.txt" read-line) ; => "hello"
(delete-file "hello.txt")
```
