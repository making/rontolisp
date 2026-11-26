# call-with-output-file

`(call-with-output-file string proc)`

Creates the file named `string` as by `open-output-file`, calls `proc` with the port, closes the port when `proc` returns, and returns what `proc` returned. If `proc` does not return, the port stays open.

```scheme
(call-with-output-file "out.txt" (lambda (p) (write '(1 2) p)))
(call-with-input-file "out.txt" read) ; => (1 2)
(delete-file "out.txt")
```
