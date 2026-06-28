# open

`(open filename &optional direction)`

Opens a file and returns a stream. The optional direction must be the literal keyword `:input` (the default -- open for reading) or `:output` (create or truncate, open for writing); it must be a literal so the compilers can pick the file mode statically. The returned stream is an opaque handle (an index into a stream table on the interpreter/JVM, the WASI file descriptor on WASM) valid only within the producing run, and should be passed to `read`/`read-line`/`write-line` and then `close`. On WASM the path is resolved against the first preopened directory, so run with `--dir`. Prefer `with-open-file`, which closes the stream automatically.

```console
(let ((s (open "data.txt")))
  (print (read-line s))
  (close s))
```

This opens `data.txt` for input, reads its first line, and closes the stream. Passing `:output` instead would create or truncate the file for writing.
