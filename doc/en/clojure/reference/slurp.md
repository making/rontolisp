# slurp

`(slurp path-or-reader)`

Answers the whole file as a string. Runs on the interpreter and the JVM; on
wasm it needs a `--dir` preopen covering the path. An open reader (a
`clojure.java.io/reader`, a Ring request [`:body`](ring.md)) is read to its end and
closed on every backend, like the oracle: a later read of it is
`java.io.IOException: Stream closed` (of a Ring `:body`, the end of the body), and a
later close, such as `with-open`'s, does nothing. As a value a one-argument function.

```console
clojure> (slurp "/tmp/note.txt")
"a\nb"
```
