# with-input-from-file

`(with-input-from-file string thunk)`

Opens the file named `string` as by `open-input-file`, makes it the current input port while `thunk` runs, and returns what `thunk` returned. The previous current input port is restored, and the file closed, however `thunk` is left -- an escape or a raised object included (Gauche leaves the file open then).

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(with-input-from-file "hello.txt" read-line) ; => "hello"
(delete-file "hello.txt")
```
