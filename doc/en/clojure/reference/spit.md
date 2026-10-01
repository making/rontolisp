# spit

`(spit path content)` / `(spit path content :append flag)`

Writes the string to the file, answering `nil`. Without `:append` the file is
superseded; a truthy flag appends. Runs on the interpreter and the JVM -- there
is no filesystem on wasm. As a value a path, content and optional flag.

```console
clojure> (spit "/tmp/note.txt" "a\n")
nil
clojure> (spit "/tmp/note.txt" "b" :append true)
nil
```
