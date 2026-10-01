# line-seq

`(line-seq path)`

Answers the file's lines as a strict list. The oracle takes a reader and
answers lazily -- here the path reads strictly, like every other seq. Runs on
the interpreter and the JVM. As a value a one-argument lambda.

```console
clojure> (line-seq "/tmp/note.txt")
("a" "b")
```
