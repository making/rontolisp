# clojure.java.io/reader

`(clojure.java.io/reader path)` / `(jio/reader path)` with `[clojure.java.io :as jio]`

Opens a buffered reader over the file, through the same file-stream runtime
`slurp` reads through. `line-seq` reads its lines without closing it;
`with-open` closes it. Wires like `clojure.string` (`:as`, `:refer`, or the
fully-qualified spelling); it is the only `clojure.java.io` var, and any other
is an error. Runs on the interpreter and the JVM; on wasm it needs a `--dir`
preopen covering the path -- without one the open signals the file-error.
As a value a one-argument lambda over the same open.

```console
clojure> (ns demo (:require [clojure.java.io :as jio]))
nil
clojure> (with-open [r (jio/reader "/tmp/note.txt")] (line-seq r))
("a" "b")
```
