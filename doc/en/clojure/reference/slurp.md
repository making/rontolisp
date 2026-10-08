# slurp

`(slurp path-or-reader)`

Answers the whole file as a string. Runs on the interpreter and the JVM; on
wasm it needs a `--dir` preopen covering the path. An open reader (a
`clojure.java.io/reader`, a Ring request [`:body`](ring.md)) is read to its end on
every backend and left open for its owner to close; the oracle closes it. As a value a
one-argument function.

```console
clojure> (slurp "/tmp/note.txt")
"a\nb"
```
