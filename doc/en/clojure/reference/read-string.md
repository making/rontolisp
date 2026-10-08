# read-string

`(read-string s)` / `(read-string opts s)`

Reads the first datum of the string and answers it as data: what a quote of the same
text answers, so `(= (read-string "[1 :k]") '[1 :k])` holds. Numbers, strings,
characters, keywords (`::kw` in the calling namespace), symbols, lists, vectors, maps and
sets read like the source reader reads them, namespace maps (`#:ns{...}`, `#::{...}`)
included; reader metadata attaches like the oracle's (`^:k` is `{:k true}`, a symbol or
string `{:tag x}`, a vector `{:param-tags v}`; a symbol carries none) and `#_` discards. A
repeated map key or set member is the oracle's `Duplicate key`, and a keyword or symbol the
oracle does not read (`a:`, `x/`, `//`) its `Invalid token`. A
record literal (`#ns.Name{...}` / `#ns.Name[...]`) builds the record of a class the
program defines, over its unevaluated body. Text after the first datum is ignored. Empty
input signals `EOF while reading`, unless the options map has an `:eof` entry, which is
then the answer. Reader conditionals are refused (`Conditional read not allowed`) unless
the options map holds `:read-cond :allow`; then they read like in a `.cljc` file, a
`:features` set adding features to `:rontolisp`, `:clj` and `:default`
(`:read-cond :preserve` is refused at the first `#?`). `#=` read-time evaluation and
tagged literals are refused like in source, and `@x` reads `(deref x)` like `'@x` does (the oracle:
`(clojure.core/deref x)`). Runs on every backend; as a value, one or two arguments.

```clojure
(defrecord Point [x y])
(println (read-string "[1 :k \"s\" (a b)]"))
(println (= (read-string "#user.Point{:x 1 :y 2}") (->Point 1 2)))
(println (read-string {:eof :none} ""))
(println (read-string {:read-cond :allow} "[#?(:cljs 1 :clj 2) #?@(:clj [3 4])]"))
(println (read-string {:read-cond :allow :features #{:cljs}} "#?(:cljs 1 :clj 2)"))
```

```
[1 :k s (a b)]
true
:none
[2 3 4]
1
```
