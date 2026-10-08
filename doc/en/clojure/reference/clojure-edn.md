# clojure.edn

Reading [EDN](https://github.com/edn-format/edn), Clojure's data notation, from a string or
a stream. `clojure.edn` is loaded before the program, as in Clojure, so
`clojure.edn/read-string` works without a `require`. It runs on every backend.

| Var | Behavior |
|---|---|
| `read-string` | `(read-string s)` / `(read-string opts s)`: the first datum of the string; `nil` for `nil`, and at the end of input `nil` (one argument), the `:eof` option, or an `EOF while reading` error |
| `read` | `(read)` / `(read stream)` / `(read opts stream)`: one datum from the stream (`*in*` for none), which is left right after it; at the end of input the `:eof` option or an error |

```clojure
(require '[clojure.edn :as edn])
(edn/read-string "{:id 7, :tags #{\"x\"}, :point [1.5 -2]}")
; => {:id 7, :tags #{"x"}, :point [1.5 -2]}
(edn/read-string "")
; => nil
(edn/read-string {:eof :done} "")
; => :done
(edn/read-string "#:user{:name \"ann\"}")
; => #:user{:name "ann"}
(meta (edn/read-string "^:private [1]"))
; => {:private true}
```

EDN is data only. The quote is part of a symbol (`'a` reads the symbol `'a`); the syntax-quote,
`~`, `@`, `#'`, `#(...)`, `#"..."`, `#=` and `#?` are refused with the oracle's messages, and
so is an auto-resolved `::keyword`. A number starts with a digit or a sign and a digit, so
`.5` is a symbol. A repeated map key or set member is a `Duplicate key` error.

## Tagged literals

`#tag value` reads `value` and calls the function the `:readers` map holds for the tag
symbol, else uses the built-in `#inst` and `#uuid` (their
[values](instants.md), read like in source), else calls the `:default` function with the tag
and the value. A tag none of them takes is `No reader function for tag`. A reader may be any
function value: a function, a var, a keyword or a map. Like the oracle's, an EDN read asks
neither `*data-readers*` nor `*default-data-reader-fn*`.

```clojure
(require '[clojure.edn :as edn])
(edn/read-string {:readers {'cm (fn [n] (/ n 100.0))}} "[#cm 150 #cm 20]")
; => [1.5 0.2]
(edn/read-string {:default (fn [tag value] {:tag tag :value value})} "#my/point [1 2]")
; => {:tag my/point, :value [1 2]}
(edn/read-string "[#inst \"2020-06-15T10:20:30Z\" #uuid \"1-1-1-1-1\"]")
; => [#inst "2020-06-15T10:20:30.000-00:00" #uuid "00000001-0001-0001-0001-000000000001"]
```

## Differences

- `read` takes any character stream, also a plain `clojure.java.io/reader`, where the
  oracle requires a `java.io.PushbackReader`.
- `N` and `M` numbers read as plain integers and exact ratios ([Syntax](../syntax.md#numbers)).
- A symbol carries no metadata, so `^:k sym` reads the bare symbol.
