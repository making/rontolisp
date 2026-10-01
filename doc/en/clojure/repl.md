# REPL

With no file, `--source-language clojure` starts a Clojure REPL (`clojure> ` prompt).
A form may span lines; completeness is decided by bracket counting over `()[]{}` outside
strings and comments. Definitions typed at separate prompts see each other, as they
would in one file: every buffer declares its top-level `def`/`defn` names before it runs,
so a later buffer may call what an earlier one defined. An `(ns name)` or `(in-ns 'name)`
buffer switches the `*ns*` the `::`-keywords below it resolve against, like a file's own
`ns` form. The value echo renders in Clojure
notation, readably.

```console
$ rontolisp --source-language clojure
clojure> (defn twice [x] (* 2 x))
twice
clojure> (twice 21)
42
```
