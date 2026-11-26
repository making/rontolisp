# with-meta

`(with-meta obj metadata)`

Answers a copy of `obj` carrying the map `metadata` (`nil` for none), which
[meta](meta.md) reads back. The original keeps its own metadata, and `=` ignores
metadata. Maps, vectors, lists, sets, records, `reify` values, lazy seqs, functions and
symbols carry it; a string, number, keyword or other value signals, like the oracle. A
symbol's copy is `=` to the symbol and hashes like it, while `identical?` tells them
apart, and every symbol verb (`name`, `symbol?`, `case`, a map lookup, printing ...)
reads it as its symbol. As a map key or set member a symbol keeps no metadata, where
the oracle's does.

Metadata changes dispatch only for a protocol declared `:extend-via-metadata true`
(see [defprotocol](defprotocol.md)). `assoc` keeps the source's metadata; any other
value derived from the copy (`conj`, `dissoc`, ...) starts without metadata, where the
oracle keeps it.

Reader metadata (`^:private`, `^:dynamic`, `^{...}` attr maps, type hints) on a name
or a local parses and drops; only `binding` reads `^:dynamic`. On a vector, map or set
literal it attaches, like the oracle's reader: `^:k [1]` carries `{:k true}`. A quoted
symbol or collection carries it too, and so does a macro's argument.

Under `*print-meta*` true, `pr`, `prn`, `pr-str` and `str` write non-empty metadata ahead
of the value (`^{:k 1} [1 2]`, a lone `:tag` as `^String [1]`); `print` and `println`
never do, and neither does `str` of a symbol.

```clojure
(def v (with-meta [1 2] {:tag :x}))
(println v (meta v))  ; [1 2] {:tag :x}
(println (meta [1 2])) ; nil
(def s (with-meta 'x {:k 1}))
(println s (meta s) (meta 'x) (= s 'x)) ; x {:k 1} nil true
```

```clojure
(binding [*print-meta* true] (prn (with-meta [1 2] {:k 1})))
```

```
^{:k 1} [1 2]
```
