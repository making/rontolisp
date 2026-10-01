# line-seq

`(line-seq path-or-reader)`

Answers the lines as a strict list -- of a path, opened and closed around the
read, or of an open reader (for example a `clojure.java.io/reader`), which is
read but never closed (`with-open` owns closing). The oracle takes a reader and
answers lazily -- here both arities read strictly, like every other seq. Runs on
the interpreter and the JVM. As a value a one-argument lambda.

```console
clojure> (line-seq "/tmp/note.txt")
("a" "b")
```
