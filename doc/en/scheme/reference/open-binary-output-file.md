# open-binary-output-file

`(open-binary-output-file string)`

Creates the file named `string`, emptying it if it exists, and returns a binary output port writing its bytes. A file that cannot be created raises an error `file-error?` answers `#t` for. Close the port with `close-port`, as for [open-output-file](open-output-file.md).

```scheme
(define p (open-binary-output-file "bytes.bin"))
(write-bytevector #u8(1 2 255) p)
(close-port p)
(call-with-port (open-binary-input-file "bytes.bin") (lambda (in) (read-bytevector 10 in))) ; => #u8(1 2 255)
(delete-file "bytes.bin")
```
