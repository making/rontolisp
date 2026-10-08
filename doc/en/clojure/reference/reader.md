# clojure.java.io/reader

`(clojure.java.io/reader x & opts)` / `(jio/reader path)` with `[clojure.java.io :as jio]`

Opens a buffered reader over a path, a File, a URL, a URI or a byte stream, through the
`IOFactory` protocol of [clojure.java.io](clojure-java-io.md); `:encoding` names the
charset, UTF-8 by default. Of an open reader (a Ring request [`:body`](ring.md)) it
answers that reader. `line-seq` reads its lines without closing it; `with-open` closes it.
Wires like `clojure.string` (`:as`, `:refer`, or the fully-qualified spelling). Runs on
every backend; on wasm a file needs a `--dir` preopen covering it -- without one the open
is the oracle's `java.io.FileNotFoundException`. As a value a function of the same
arguments.

```console
clojure> (ns demo (:require [clojure.java.io :as jio]))
nil
clojure> (with-open [r (jio/reader "/tmp/note.txt")] (line-seq r))
("a" "b")
```
