# (scheme file)

File ports, and asking for and deleting a file. A file name is a string; a relative one is
resolved against the current directory. On WebAssembly a file is reachable only inside a
directory the host preopens (`wasmtime run --dir`).

| Name | Example | Result |
|---|---|---|
| `open-input-file` | `(open-input-file "hello.txt")` | a textual input port reading `hello.txt` |
| `open-output-file` | `(open-output-file "out.txt")` | a textual output port writing `out.txt`, emptied first |
| `open-binary-input-file` | `(open-binary-input-file "hello.txt")` | a binary input port reading `hello.txt` |
| `open-binary-output-file` | `(open-binary-output-file "bytes.bin")` | a binary output port writing `bytes.bin`, emptied first |
| `call-with-input-file` | `(call-with-input-file "hello.txt" read-line)` | `"hello"` |
| `call-with-output-file` | `(call-with-output-file "out.txt" (lambda (p) (write '(1 2) p)))` | writes `(1 2)` to `out.txt` and closes it |
| `with-input-from-file` | `(with-input-from-file "hello.txt" read-line)` | `"hello"` |
| `with-output-to-file` | `(with-output-to-file "out.txt" (lambda () (display "hi")))` | writes `hi` to `out.txt` and closes it |
| `file-exists?` | `(file-exists? "no-such-file.txt")` | `#f` |
| `delete-file` | `(delete-file "hello.txt")` | removes `hello.txt` |
