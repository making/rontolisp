# spit

`(spit path content)` / `(spit path content :append flag)`

Writes `(str content)` to the file, answering `nil`: a string is written as
before, any other value through the `str` spelling (`(spit f '(1 2))` writes
`(1 2)`), `nil` writing nothing. Without `:append` the file is
superseded; a truthy flag appends. Runs on the interpreter and the JVM; on wasm
it needs a `--dir` preopen covering the path. As a value a path, content and optional flag.

```console
clojure> (spit "/tmp/note.txt" "a\n")
nil
clojure> (spit "/tmp/note.txt" "b" :append true)
nil
clojure> (spit "/tmp/note.txt" '(1 2))
nil
```
