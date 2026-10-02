# slurp

`(slurp path)`

Answers the whole file as a string. Runs on the interpreter and the JVM; on
wasm it needs a `--dir` preopen covering the path. As a value a one-argument lambda.

```console
clojure> (slurp "/tmp/note.txt")
"a\nb"
```
