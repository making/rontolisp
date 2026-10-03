# with-in-str

`(with-in-str s body...)`

Runs the body with `*in*` bound to a reader over the string `s` and answers the
body's value, so [read-line](read-line.md), [read](read.md) and `(.read *in*)`
read the string. `#'*in*` is thread-bound inside, like any `binding` of it.

```clojure
(println (with-in-str "one\ntwo" [(read-line) (read-line) (read-line)])) ; [one two nil]
(println (with-in-str "(+ 1 2) :k" [(read) (read)])) ; [(+ 1 2) :k]
```
