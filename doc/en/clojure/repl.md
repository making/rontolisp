# REPL

With no file, `--source-language clojure` starts a Clojure REPL (`clojure> ` prompt).
A form may span lines; completeness is decided by bracket counting over `()[]{}` outside
strings and comments. Definitions typed at separate prompts see each other, as they
would in one file: every buffer declares its top-level `def`/`defn` names before it runs,
so a later buffer may call what an earlier one defined. An `(ns name)` or `(in-ns 'name)`
buffer switches the `*ns*` the `::`-keywords below it resolve against, like a file's own
`ns` form. The value echo renders in Clojure
notation, readably; a top-level `def`, `defn`, `defn-`, `defmacro`, `defmulti` or
`defonce` echoes the var it defined (`#'user/twice`), and a `defonce` over a bound var `nil`.
`defprotocol` echoes its name (`P`), `defrecord` and `deftype` their class name (`user.R`),
`declare` the last name's var, and a `def` nested in another form its var as well. In a file a
nested `def` answers the value.

Like the oracle's REPL, `*1`, `*2` and `*3` hold the values of the last three inputs (an
`ns` input records `nil`) and `*e` the last exception an input threw, which leaves them
alone; a refusal while the input is read or lowered is no exception and records nothing.
`*repl*` is `true` and bound, `*file*` is `"NO_SOURCE_PATH"` and `*source-path*`
`"NO_SOURCE_FILE"`.

```console
$ rontolisp --source-language clojure
clojure> (defn twice [x] (* 2 x))
#'user/twice
clojure> (twice 21)
42
clojure> [*1 *2]
[42 #'user/twice]
clojure> (/ 1 0)
Error: Division by zero
clojure> (ex-message *e)
"Division by zero"
```
