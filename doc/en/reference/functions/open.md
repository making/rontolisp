# open

`(open filename &optional direction element-type)`

Opens a file and returns a stream. The optional direction is `:input` (the default -- open for reading) or `:output` (create or truncate, open for writing). The keyword-argument shape `(open filename :direction :output :if-exists :append)` opens for writing WITHOUT truncating, so every write lands at the end of an existing file; the other `:if-exists`/`:if-does-not-exist`/`:external-format` values are accepted only where they name the native behavior already (`:supersede`, `:create`/`:error`, `:utf-8`/`:default`). The optional element type selects the stream kind: `'character` (the default) opens a text stream for `read`/`read-line`/`write-line`, and `'(unsigned-byte 8)` -- or the unsized `'unsigned-byte` -- opens a binary stream for `read-byte`/`write-byte`/`read-sequence`/`write-sequence`. In the keyword shape an option value may be COMPUTED (`(open path :direction dir :element-type type)`): it is read when the call runs and dispatched onto the matching literal shape, which is what lets a portable wrapper take its options as arguments. A literal value is resolved at compile time exactly as before, and a value outside the supported set signals an error when the call runs. The returned stream is an opaque handle (an index into a stream table on the interpreter/JVM, the WASI file descriptor on WASM) valid only within the producing run, and should be passed to the matching read/write functions and then `close`. On WASM the path is resolved against the preopened directories -- a relative path against the first one, an absolute path against the preopened directory whose name is its longest prefix -- so run with `--dir`. Prefer `with-open-file`, which closes the stream automatically.

```console
(let ((s (open "data.txt")))
  (print (read-line s))
  (close s))
```

This opens `data.txt` for input, reads its first line, and closes the stream. Passing `:output` instead would create or truncate the file for writing; `(open "data.bin" :input '(unsigned-byte 8))` opens the same kind of handle in binary mode.
