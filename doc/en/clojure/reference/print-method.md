# print-method

`(defmethod print-method Type [x writer] body...)` / `(print-method x writer)`

`clojure.core/print-method` is the printer's multimethod: a method a program defines for one
of its records or deftypes is how `pr`, `prn`, `print`, `println`, `pr-str`, `prn-str`,
`print-str`, `println-str` and the REPL write a value of the type, also inside a collection
and as part of `str` of one. The writer is the stream being written: `.write` a string to it,
or `binding [*out* writer]` and print. A `pr` inside the method under `print` writes as
`print` does (`*print-readably*` is nil there, like the oracle's `print`). Called,
`print-method` writes `x` through its type's method, or as `pr` writes it without one.
`remove-method` brings the default back, `get-method` answers the method (without one,
`print-method` itself).

A method for a `:type` keyword prints every value carrying it in its metadata
(`(:type (meta x))`), ahead of the record-or-deftype tag's own -- the oracle dispatches
`print-method` on the `:type` entry first.

Deviations: only a program's own records, deftypes and `:type` keywords take a method --
one for a host or `clojure.lang` class, `Object` or nil is refused by name, and so are
`methods` and `prefer-method` of `print-method`; `str` of the value itself answers what the
method writes where the oracle answers its `toString`.

```clojure
(defrecord Point [x y])
(defmethod print-method Point [p w] (.write w (str "<" (:x p) "," (:y p) ">")))
(prn (->Point 1 2) [(->Point 3 4)]) ; <1,2> [<3,4>]
(println (pr-str (->Point 5 6))) ; <5,6>
```
