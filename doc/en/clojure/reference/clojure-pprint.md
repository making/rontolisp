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
| `code-dispatch` | The dispatch for Clojure code, a multimethod on `class` too: each defining and control form in its own layout |
| `*print-pprint-dispatch*`, `with-pprint-dispatch`, `set-pprint-dispatch` | The function `pprint` hands each value to: bound for a body, or replaced |
| `pprint-logical-block` | `(pprint-logical-block options* body)`: `body` as a logical block, the unit a layout breaks; options `:prefix`, `:per-line-prefix`, `:suffix`. Past `*print-level*` it writes `#` |
| `print-length-loop` | A `loop` whose body runs at most `*print-length*` times, then writes `...` |
| `write-out` | `(write-out x)`: `x` written through the dispatch inside a block |
| `pprint-newline` | `(pprint-newline kind)`: a conditional newline, `:linear` (taken when the block does not fit), `:fill` (when the next part does not fit on the line), `:miser` (in miser style only) or `:mandatory` |
| `pprint-indent` | `(pprint-indent relative-to n)`: the block's continuation lines indented `n` past its start (`:block`) or past the current column (`:current`) |
| `fresh-line` | A newline unless the pretty print in progress is at the start of a line; outside a pretty print, always a newline |
| `*print-right-margin*`, `*print-miser-width*` | The margin, 72 (`nil` for none), and how close to it a block may start before it prints in miser style, 40 (`nil` for never) |
| `*print-pretty*`, `*print-suppress-namespaces*`, `*print-base*`, `*print-radix*` | `write`'s defaults: pretty, namespaces kept, base 10, no radix mark |
| `pprint-tab` | Throws `UnsupportedOperationException`, as in Clojure |
| `get-pretty-writer` | Answers its writer |
| `cl-format` | `(cl-format writer control & args)`: `args` formatted by Common Lisp's format directives in `control`, to a string answered for a `nil` writer, to `*out*` for `true`, else to `writer`; see [cl-format](#cl-format) |
| `formatter`, `formatter-out` | `(formatter control)`: a function of a writer and arguments formatting them as `cl-format` does, its control string compiled once; `(formatter-out control)`: a function of arguments writing to `*out*` as it is, for a dispatch function |

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

`code-dispatch` lays code out the way Clojure's pretty printer does: `defn`, `let`, `if`,
`cond`, `condp`, `->`, `ns` and the like each in their own layout, and an anonymous function
form as its `#(...)` literal:

```clojure
(require '[clojure.pprint :as pp])
(pp/with-pprint-dispatch pp/code-dispatch
  (pp/pprint '(defn greet "Greets a person." [person]
                (let [n (:name person)]
                  (when (seq n) (println "Hello," n "- glad to see you again"))))))
(pp/with-pprint-dispatch pp/code-dispatch
  (pp/pprint '(fn* [p1 p2] (+ p1 (* p2 p2)))))
```

```
(defn greet
  "Greets a person."
  [person]
  (let [n (:name person)]
    (when (seq n) (println "Hello," n "- glad to see you again"))))
#(+ %1 (* %2 %2))
```

## cl-format

`cl-format` is Common Lisp's `format` over Clojure values: `~A` and `~S` print as `print` and
`pr` do, `~{` walks any collection or seq, and `~:[` tests Clojure truth, so `false` takes the
first clause like `nil`.

| Directive | Writes |
|---|---|
| `~A`, `~S` | The argument as `print` / `pr` would, an integer or ratio in `*print-base*` and `*print-radix*`; `~mincol,colinc,minpad,padcharA` pads it, on the left with `@` |
| `~D`, `~B`, `~O`, `~X`, `~radixR` | An integer in base 10, 2, 8, 16 or `radix`; `~mincol,padchar,commachar,intervalD`, `:` groups the digits, `@` signs a positive one |
| `~R`, `~:R`, `~@R`, `~:@R` | A number in English words, as an ordinal, in Roman numerals, in old Roman numerals |
| `~P`, `~@P` | `s` (`ies`) unless the argument is 1; `:` backs up to the argument before |
| `~C`, `~:C`, `~@C` | A character, its name, as `pr` writes it |
| `~F`, `~E`, `~G`, `~$` | A number in fixed, exponential, general and monetary notation, with Common Lisp's parameters |
| `~%`, `~&`, `~\|`, `~~`, `~T` | A newline, a fresh line, a page, a tilde, blanks to a column |
| `~*`, `~:*`, `~@*` | Skips arguments, backs up, goes to an argument |
| `~[...~;...~]`, `~:[`, `~@[` | The clause the argument selects: by index, by truth, when it is true |
| `~{...~}`, `~:{`, `~@{`, `~:@{` | The body over the elements of a collection, over its sublists, over the remaining arguments, over those as sublists |
| `~^`, `~:^` | Ends the enclosing run when no argument (no sublist) is left |
| `~(...~)`, `~:(`, `~@(`, `~:@(` | The body lowercased, its words capitalized, its first word capitalized, uppercased |
| `~?`, `~@?` | Another control string over a list argument, over the remaining arguments |
| `~<...~>` | The clauses justified within a field |
| `~W`, `~<...~:>`, `~_`, `~I` | The argument through the pretty print dispatch, a logical block, a conditional newline (`:` fill, `@` miser, `:@` mandatory), an indentation |

```clojure
(require '[clojure.pprint :as pp])
(pp/cl-format true "There ~[are~;is~:;are~]~:* ~d result~:p: ~{~d~^, ~}~%" 3 [46 38 22])
(println (pp/cl-format nil "~:d ~r ~:r ~@r" 1234567 42 3 1994))
(println (pp/cl-format nil "~,2f|~10,3e|~$|~8,'0x" 3.14159 12345.678 1.5 255))
(println (pp/cl-format nil "~:(~a~) ~@(~a~) ~:@(~a~)" "hello world" "hELLO" "loud"))
(println (pp/cl-format nil "~{~a~^, ~}|~:{<~a ~a>~}|~20<left~;right~>|" [1 2 3] [[1 2] [3 4]]))
(println (pp/cl-format nil "~a~12t~a~24t~a" "name" "lang" "year"))
```

```
There are 3 results: 46, 38, 22
1,234,567 forty-two third MCMXCIV
3.14|  1.235E+4|1.50|000000ff
Hello World Hello LOUD
1, 2, 3|<1 2><3 4>|left           right|
name        lang        year
```

The pretty printing directives add to the pretty print in progress, so a dispatch function
can lay its value out with `formatter-out`. Outside one, a control string that needs a
pretty writer (`~W`, `~<`, `~T`, `~&`) gets one of its own, starting at column 0.

```clojure
(require '[clojure.pprint :as pp])
(defn json-dispatch [x]
  (cond (map? x) ((pp/formatter-out "~<{~;~@{~<~w:~_~w~:>~^, ~_~}~;}~:>")
                  (for [[k v] x] [(name k) v]))
        (sequential? x) ((pp/formatter-out "~<[~;~@{~w~^, ~:_~}~;]~:>") x)
        (string? x) (pr x)
        (nil? x) (print "null")
        :else (print x)))
(binding [pp/*print-right-margin* 50 pp/*print-miser-width* nil]
  (pp/with-pprint-dispatch json-dispatch
    (pp/pprint (sorted-map :name "rontolisp" :tags ["lisp" "clojure" "wasm"] :scores (vec (range 0 60 4))))))
```

```
{"name":"rontolisp",
 "scores":
 [0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44,
  48, 52, 56],
 "tags":["lisp", "clojure", "wasm"]}
```

A malformed control string is a `RuntimeException` whose message shows the string with a
caret under the fault.

## Differences

- A decimal literal is a ratio here, so `~A` of `1.5M` writes `3/2`, and an integer is never
  a long that overflows: `~D` of `-9223372036854775808` writes it where Clojure throws.
- Where Clojure's refusal message is the JVM's own (the `ClassCastException` of a pretty
  printing directive whose `*out*` is no pretty writer, a `NullPointerException`), the class
  is the same and the message differs.
- `get-pretty-writer` answers its writer unchanged: each `pprint` or `write` lays its
  output out by itself, from column 0, within the margin bound when it runs. Clojure's
  pretty writer keeps the margin it was made with and continues from the column of what
  was written through it before.
