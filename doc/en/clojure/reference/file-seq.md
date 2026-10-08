# file-seq

`(file-seq dir)`

A lazy seq of the `java.io.File` `dir` and, when it is a directory, every File below it,
depth first, each directory ahead of what it holds -- the oracle's `tree-seq` over
`isDirectory` and `listFiles`. A directory's Files come in the order the host lists them,
unsorted, like the oracle's; anything but a File is the oracle's `ClassCastException`. Runs on
every backend; on wasm the tree needs a `--dir` preopen covering it. As a value a one-argument
function. Files are [clojure.java.io](clojure-java-io.md)'s.

```console
clojure> (require '[clojure.java.io :as io])
nil
clojure> (sort (map str (file-seq (io/file "/tmp/notes"))))
("/tmp/notes" "/tmp/notes/a.txt" "/tmp/notes/b.txt")
clojure> (filter #(.isFile %) (file-seq (io/file "/tmp/notes")))
(#object[java.io.File "/tmp/notes/b.txt"] #object[java.io.File "/tmp/notes/a.txt"])
```
