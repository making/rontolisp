# line-seq

`(line-seq path-or-reader)`

Answers the lines as a strict list -- of an open reader (for example a
`clojure.java.io/reader`), which is read but never closed (`with-open` owns closing), or of
a path, a File, a URL or a byte stream ([clojure.java.io](clojure-java-io.md)), opened and
closed around the read. The oracle takes a reader only and answers lazily -- here every
argument reads strictly, like every other seq. Runs on every backend; on wasm a file needs a
`--dir` preopen covering it. As a value a one-argument lambda.

```console
clojure> (line-seq "/tmp/note.txt")
("a" "b")
```
