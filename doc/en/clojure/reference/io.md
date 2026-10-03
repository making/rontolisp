# IO

File entry points over the `eval` IO layer. They run on the interpreter and the
JVM; on wasm they need a `--dir` preopen covering the path, like `open`/`with-open-file` --
without one the open signals the file-error. `read-string` and `read` read data back --
what `spit` wrote included -- on every backend. `format` renders Java-format strings over
Clojure-notation arguments.

| Name | Example | Result |
|---|---|---|
| `spit` | `(spit path "a\n")` | `nil` |
| `slurp` | `(slurp path)` | `"a\n"` |
| `line-seq` | `(line-seq path-or-reader)` | `("a")` |
| `clojure.java.io/reader` | `(jio/reader path)` | a reader |
| `read-string` | `(read-string "[1 :k]")` | `[1 :k]` |
| `read` | `(read (java.io.PushbackReader. (jio/reader path)))` | the first datum |
| `format` | `(format "%s=%d" :a 5)` | `":a=5"` |
| `with-open` | `(with-open [] :ok)` | `:ok` |
| `with-out-str` | `(with-out-str (print 1))` | `"1"` |
