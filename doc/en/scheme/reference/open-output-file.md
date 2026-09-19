# open-output-file

`(open-output-file string)`

Creates the file named `string`, emptying it if it exists, and returns a textual output port writing it. A file that cannot be created raises an error `file-error?` answers `#t` for. What is written may stay buffered until the port is closed with `close-port` or the program ends.

```scheme
(define p (open-output-file "out.txt"))
(write '(1 "two") p)
(close-port p)
(call-with-input-file "out.txt" read) ; => (1 "two")
(delete-file "out.txt")
```
