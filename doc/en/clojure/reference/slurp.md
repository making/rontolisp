# slurp

`(slurp f & opts)`

Answers the whole of `f` as a string: a path's file, opened and closed around the read, or
what a [clojure.java.io](clojure-java-io.md) reader over `f` reads -- a File, a URL, a byte
stream, a type extended to `IOFactory` -- in the charset `:encoding` names (UTF-8 by
default). A string spelling a URL is that URL in a program that loads clojure.java.io or reads
an `http:` URL, which goes through `rontolisp:fetch` ([HTTP URLs](clojure-java-io.md#http-urls)):
`(slurp "https://...")` reads the reply. Runs on every backend; on wasm a file needs a `--dir`
preopen covering it. An open
reader (a `clojure.java.io/reader`, a Ring request [`:body`](ring.md)) is read to its end and
closed on every backend, like the oracle: a later read of it is
`java.io.IOException: Stream closed` (of a Ring `:body`, the end of the body), and a
later close, such as `with-open`'s, does nothing. As a value a one-argument function.

```console
clojure> (slurp "/tmp/note.txt")
"a\nb"
clojure> (slurp "/tmp/latin1.txt" :encoding "ISO-8859-1")
"é"
```
