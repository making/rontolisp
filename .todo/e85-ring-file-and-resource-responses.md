# e85. Ring: a `java.io.File` body, `file-response` and `resource-response`

Difficulty: Medium

`clojure.java.io` makes `java.io.File` and URL values on every backend since 2026-10-08
(`.kb/clojure-frontend.md`, "clojure.java.io"), but the Ring side still treats them as host
objects: a handler answering `{:body (io/file "x")}` signals (the transport answers 500,
`doc/*/clojure/reference/ring.md`), and `ring.util.response/file-response`, `url-response`,
`resource-response` and `resource-data` are refused by name
(`ClojureBuiltinNamespaces`: "it serves a java.io.File", "it reads a java.net.URL", "it reads
a class-loader resource").

## Plan

1. Measure ring-core 1.15.5 on clj 1.12.6: a File body's headers (`Content-Length`,
   `Last-Modified`), `file-response` options (`:root`, `:index-files?`, `:allow-symlinks?`),
   `resource-response` over a directory and a jar resource.
2. The adapter streams a File body (`%clojure-ring-app`, on every transport); the four
   functions in the built-in `ring.util.response` over `clojure.java.io`, `resource` through
   the source path ("Resources" there).
3. Cases in the Ring adapter's test on the transports, `ring.md`/`ring-util.md`.
