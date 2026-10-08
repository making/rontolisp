# clojure.pprint

Pretty printing: data laid out within a right margin, through a dispatch function a program
can extend or replace. Require `clojure.pprint` to use it; it is Clojure source written for
rontolisp from the documented behavior of Clojure's namespace, over a layout engine that
makes the same line-breaking decisions as Clojure's pretty printer, and runs the same on
every backend.

| Var | Behavior |
|---|---|
| `pprint` | `(pprint x)`, `(pprint x writer)`: `x` laid out within `*print-right-margin*` on `*out*` (or `writer`), then a newline |
| `pp` | `(pp)`: `pprint` of `*1` |
| `write` | `(write x & options)`: `x` written as the options say: `:stream` (a writer; `true`, the default, for `*out*`; `nil` to answer the text), `:pretty`, `:right-margin`, `:miser-width`, `:dispatch`, `:length`, `:level`, `:readably`, `:suppress-namespaces`, `:base`, `:radix` |
| `print-table` | `(print-table rows)`, `(print-table ks rows)`: the maps of `rows` as a table, one right-aligned column per key of `ks` (the first row's keys when absent) |
| `simple-dispatch` | The default dispatch, a multimethod on `class`; a program adds a method for its own record or type |
| `*print-pprint-dispatch*`, `with-pprint-dispatch`, `set-pprint-dispatch` | The function `pprint` hands each value to: bound for a body, or replaced |
| `pprint-logical-block` | `(pprint-logical-block options* body)`: `body` as a logical block, the unit a layout breaks; options `:prefix`, `:per-line-prefix`, `:suffix`. Past `*print-level*` it writes `#` |
| `print-length-loop` | A `loop` whose body runs at most `*print-length*` times, then writes `...` |
| `write-out` | `(write-out x)`: `x` written through the dispatch inside a block |
| `pprint-newline` | `(pprint-newline kind)`: a conditional newline, `:linear` (taken when the block does not fit), `:fill` (when the next part does not fit on the line), `:miser` (in miser style only) or `:mandatory` |
| `pprint-indent` | `(pprint-indent relative-to n)`: the block's continuation lines indented `n` past its start (`:block`) or past the current column (`:current`) |
| `fresh-line` | A newline unless the output is at the start of a line |
| `*print-right-margin*`, `*print-miser-width*` | The margin, 72 (`nil` for none), and how close to it a block may start before it prints in miser style, 40 (`nil` for never) |
| `*print-pretty*`, `*print-suppress-namespaces*`, `*print-base*`, `*print-radix*` | `write`'s defaults: pretty, namespaces kept, base 10, no radix mark |
| `pprint-tab` | Throws `UnsupportedOperationException`, as in Clojure |
| `get-pretty-writer` | Answers its writer |

A collection that fits on the rest of the line prints on it. One that does not puts each
member on a line of its own, and a map entry's value moves below its key when the entry
does not fit either. `*print-length*`, `*print-level*`, `*print-meta*` and
`*print-namespace-maps*` apply as they do to `pr`, and a map's or a set's members come in
the order `pr` prints them.

```clojure
(require '[clojure.pprint :as pp])
(pp/pprint (sorted-map :id 7 :tags [:admin :dev] :scores (vec (range 0 60 3))))
(binding [pp/*print-right-margin* 20]
  (pp/pprint '(defn greet [name] (str "Hello, " name "!"))))
(pp/print-table [:lang :year] [{:lang "Clojure" :year 2007} {:lang "Common Lisp" :year 1984}])
```

```
{:id 7,
 :scores [0 3 6 9 12 15 18 21 24 27 30 33 36 39 42 45 48 51 54 57],
 :tags [:admin :dev]}
(defn
 greet
 [name]
 (str
  "Hello, "
  name
  "!"))

|       :lang | :year |
|-------------+-------|
|     Clojure |  2007 |
| Common Lisp |  1984 |
```

`write` with `:stream nil` answers the text; `with-out-str` captures `pprint`'s:

```clojure
(require '[clojure.pprint :as pp])
(pp/write (range 5) :stream nil) ; => "(0 1 2 3 4)"
(with-out-str (pp/pprint [1 2])) ; => "[1 2]\n"
```

A method on `simple-dispatch` prints a type of the program's own:

```clojure
(require '[clojure.pprint :as pp])
(defrecord Money [amount currency])
(defmethod pp/simple-dispatch Money [m]
  (print (str "<" (:amount m) " " (:currency m) ">")))
(pp/pprint {:price (->Money 120 "JPY")})
```

```
{:price <120 JPY>}
```

A dispatch function of its own lays a value out with logical blocks and conditional newlines:

```clojure
(require '[clojure.pprint :as pp])
(defn angle-dispatch [x]
  (if (sequential? x)
    (pp/pprint-logical-block :prefix "<" :suffix ">"
      (pp/print-length-loop [s (seq x)]
        (when s
          (pp/write-out (first s))
          (when (next s)
            (print " ")
            (pp/pprint-newline :fill)
            (recur (next s))))))
    (pr x)))
(binding [pp/*print-right-margin* 24 pp/*print-miser-width* nil]
  (pp/with-pprint-dispatch angle-dispatch
    (pp/pprint (range 20))))
```

```
<0 1 2 3 4 5 6 7 8 9 10
 11 12 13 14 15 16 17
 18 19>
```

## Differences

- `cl-format`, `formatter`, `formatter-out` and `code-dispatch` are not built in; naming one
  is an error that says so.
- `get-pretty-writer` answers its writer unchanged: each `pprint` or `write` lays its
  output out by itself, from column 0, within the margin bound when it runs. Clojure's
  pretty writer keeps the margin it was made with and continues from the column of what
  was written through it before.
