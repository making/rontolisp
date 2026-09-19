# with-output-to-file

`(with-output-to-file string thunk)`

Creates the file named `string` as by `open-output-file`, makes it the current output port while `thunk` runs, and returns what `thunk` returned. The previous current output port is restored, and the file closed, however `thunk` is left -- an escape or a raised object included (Gauche leaves the file open then).

```scheme
(with-output-to-file "out.txt" (lambda () (display "hi")))
(call-with-input-file "out.txt" read-line) ; => "hi"
(delete-file "out.txt")
```
