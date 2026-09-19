# open-output-file

`(open-output-file string)`

Creates the file named `string`, emptying it if it exists, and returns a textual output port writing it. A file that cannot be created raises an error `file-error?` answers `#t` for. What is written may stay buffered until the port is closed, so close it with `close-port`: on the interpreter and the JVM, output a port left open at the end of the program still buffers is lost.

```scheme
(define p (open-output-file "out.txt"))
(write '(1 "two") p)
(close-port p)
(call-with-input-file "out.txt" read) ; => (1 "two")
(delete-file "out.txt")
```
