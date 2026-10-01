# slurp

`(slurp path)`

Answers the whole file as a string. Runs on the interpreter and the JVM --
there is no filesystem on wasm. As a value a one-argument lambda.

```console
clojure> (slurp "/tmp/note.txt")
"a\nb"
```
