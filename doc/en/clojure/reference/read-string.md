# read-string

`(read-string s)` / `(read-string opts s)`

Reads the first datum of the string and answers it as data: what a quote of the same
text answers, so `(= (read-string "[1 :k]") '[1 :k])` holds. Numbers, strings,
characters, keywords (`::kw` in the calling namespace), symbols, lists, vectors, maps and
sets read like the source reader reads them; reader metadata drops and `#_` discards. A
record literal (`#ns.Name{...}` / `#ns.Name[...]`) builds the record of a class the
program defines, over its unevaluated body. Text after the first datum is ignored. Empty
input signals `EOF while reading`, unless the options map has an `:eof` entry, which is
then the answer. `#=` read-time evaluation, reader conditionals and tagged literals are
refused like in source, and `@x` reads `(deref x)` like `'@x` does (the oracle:
`(clojure.core/deref x)`). Runs on every backend; as a value, one or two arguments.

```clojure
(defrecord Point [x y])
(println (read-string "[1 :k \"s\" (a b)]"))
(println (= (read-string "#user.Point{:x 1 :y 2}") (->Point 1 2)))
(println (read-string {:eof :none} ""))
```

```
[1 :k s (a b)]
true
:none
```
