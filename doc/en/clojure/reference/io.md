# IO

File entry points over the `eval` IO layer, on every backend: on wasm a file needs a
`--dir` preopen covering it, like `open`/`with-open-file` -- without one the open fails as
for a missing file (a `java.io.FileNotFoundException` to a `catch`). They take the Files,
URLs and streams of [clojure.java.io](clojure-java-io.md) too. `read-string` and `read` read
data back -- what `spit` wrote included -- on every backend. `format` renders Java-format
strings over Clojure-notation arguments.

| Name | Example | Result |
|---|---|---|
| `spit` | `(spit path "a\n")` | `nil` |
| `slurp` | `(slurp path-or-reader)` | `"a\n"` |
| `line-seq` | `(line-seq path-or-reader)` | `("a")` |
| `file-seq` | `(file-seq (jio/file dir))` | the Files below `dir` |
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
| `flush` | `(flush)` | `nil` |
| `printf` | `(with-out-str (printf "%d" 42))` | `"42"` |
| `char-escape-string` | `(char-escape-string \newline)` | `"\\n"` |
| `char-name-string` | `(char-name-string \newline)` | `"newline"` |
| `print-simple` | `(with-out-str (print-simple :a *out*))` | `":a"` |
| `print-dup` | `(with-out-str (print-dup {:a 1} *out*))` | `"#=(clojure.lang.PersistentArrayMap/create {:a 1})"` |
| `print-ctor` | `(with-out-str (print-ctor {:a 1} (fn [o w] (print-dup o w)) *out*))` | `"#=(clojure.lang.PersistentArrayMap. #=(clojure.lang.PersistentArrayMap/create {:a 1}))"` |
| `munge` | `(munge "foo-bar")` | `"foo_bar"` |
| `namespace-munge` | `(namespace-munge "my.ns-name")` | `"my.ns_name"` |
