# syntax-quote

`` `form `` (with `~` unquote and `~@` unquote-splicing)

Builds a form as data: a symbol naming a var the namespace sees (its own or a referred one)
qualifies with the var's namespace, so the expansion reaches it from any namespace; a
special form stays bare, while every other symbol qualifies even when it resolves to
nothing, like the oracle: a core name spells `clojure.core/name` (one a
`(:refer-clojure ...)` filter hides spells its own namespace instead), any other
unresolved spelling the defining namespace, an alias head its namespace, a class head
its fully qualified name. `~` inserts its form's value, and `~@` splices a
sequence into the enclosing list, vector, map or set. Each `x#` binds one fresh
gensym per expansion -- the same symbol at every occurrence within it, a new one
across expansions. Reader metadata on a template form travels with it, like the oracle's
`(with-meta form meta)` over the syntax-quoted map (a class tag qualifies), so
`` `(^:once fn* [] ~@body) `` and a hinted `[^String s#]` keep theirs, while an unquoted form
drops its own (`` `(def ^:private ~n 1) `` defines a public var). Outside a macro body
the template evaluates where it is written. An unquote outside any syntax-quote is an
error, as is a splice outside a sequence.

```clojure
(defmacro doc-mwhen [c & body] `(if ~c (do ~@body)))
(println (macroexpand-1 '(doc-mwhen true 1 2))) ; (if true (do 1 2))
```
