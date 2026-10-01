# syntax-quote

`` `form `` (with `~` unquote and `~@` unquote-splicing)

Builds a form as data: every symbol qualifies behind the `c%` prefix (there are no
namespaces to qualify against), `~` inserts its form's value, and `~@` splices a
sequence into the enclosing list, vector, map or set. Each `x#` binds one fresh
gensym per expansion -- the same symbol at every occurrence within it, a new one
across expansions. Outside a macro body the template evaluates where it is written.
An unquote outside any syntax-quote is an error, as is a splice outside a sequence.

```clojure
(defmacro doc-mwhen [c & body] `(if ~c (do ~@body)))
(println (macroexpand-1 '(doc-mwhen true 1 2))) ; (IF true (DO 1 2))
```
