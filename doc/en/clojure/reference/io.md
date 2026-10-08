# IO

File entry points over the `eval` IO layer. They run on the interpreter and the
JVM; on wasm they need a `--dir` preopen covering the path, like `open`/`with-open-file` --
without one the open signals the file-error. `read-string` and `read` read data back --
what `spit` wrote included -- on every backend. `format` renders Java-format strings over
Clojure-notation arguments.

| Name | Example | Result |
|---|---|---|
| `spit` | `(spit path "a\n")` | `nil` |
| `slurp` | `(slurp path-or-reader)` | `"a\n"` |
| `line-seq` | `(line-seq path-or-reader)` | `("a")` |
| `clojure.java.io/reader` | `(jio/reader path-or-reader)` | a reader |
| `read-string` | `(read-string "[1 :k]")` | `[1 :k]` |
| `read` | `(read (java.io.PushbackReader. (jio/reader path)))` | the first datum |
| `reader-conditional` | `(reader-conditional '(:clj 1) false)` | `#?(:clj 1)` |
| `tagged-literal` | `(tagged-literal 'js {})` | `#js {}` |
| `default-data-readers` | `(get default-data-readers 'inst)` | `#'clojure.instant/read-instant-date` |
| `format` | `(format "%s=%d" :a 5)` | `":a=5"` |
| `with-open` | `(with-open [] :ok)` | `:ok` |
| `with-out-str` | `(with-out-str (print 1))` | `"1"` |
| `with-in-str` | `(with-in-str "a\nb" (read-line))` | `"a"` |
| `read-line` | `(read-line)` | the next line of `*in*` |
| `print-str` | `(print-str 1 "a")` | `"1 a"` |
| `prn-str` | `(prn-str 1 "a")` | `"1 \"a\"\n"` |
| `println-str` | `(println-str 1 "a")` | `"1 a\n"` |
