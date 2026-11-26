# spit

`(spit f content)` / `(spit f content :append flag :encoding name)`

Writes `(str content)` to `f`, answering `nil`: a string is written as
before, any other value through the `str` spelling (`(spit f '(1 2))` writes
`(1 2)`; a collection's strings are quoted, so [read](read.md) reads it back),
`nil` writing nothing. `f` is a path, or anything a [clojure.java.io](clojure-java-io.md)
writer opens: a File, a byte stream, a type extended to `IOFactory`. Without `:append` the
file is superseded; a truthy flag appends. `:encoding` names the charset, UTF-8 by default.
Runs on every backend; on wasm a file needs a `--dir` preopen covering it. As a value a
path, content and optional flag.

```console
clojure> (spit "/tmp/note.txt" "a\n")
nil
clojure> (spit "/tmp/note.txt" "b" :append true)
nil
clojure> (spit "/tmp/note.txt" '(1 2))
nil
```
