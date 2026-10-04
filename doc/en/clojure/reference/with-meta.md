# with-meta

`(with-meta obj metadata)`

Answers a copy of `obj` carrying the map `metadata` (`nil` for none), which
[meta](meta.md) reads back. The original keeps its own metadata, and `=` ignores
metadata. Maps, vectors, lists, sets, records, `reify` values, lazy seqs and functions
carry it; a string, number, keyword or other value signals, like the oracle. A symbol
answers itself without metadata.

Metadata changes dispatch only for a protocol declared `:extend-via-metadata true`
(see [defprotocol](defprotocol.md)). A value derived from the copy (`assoc`, `conj`,
...) starts without metadata, where the oracle keeps it.

Reader metadata (`^:private`, `^:dynamic`, `^{...}` attr maps, type hints) on a name
or a local parses and drops; only `binding` reads `^:dynamic`. On a vector, map or set
literal it attaches, like the oracle's reader: `^:k [1]` carries `{:k true}`.

Under `*print-meta*` true, `pr`, `prn`, `pr-str` and `str` write non-empty metadata ahead
of the value (`^{:k 1} [1 2]`, a lone `:tag` as `^String [1]`); `print` and `println`
never do.

```clojure
(def v (with-meta [1 2] {:tag :x}))
(println v (meta v))  ; [1 2] {:tag :x}
(println (meta [1 2])) ; nil
```

```clojure
(binding [*print-meta* true] (prn (with-meta [1 2] {:k 1})))
```

```
^{:k 1} [1 2]
```
