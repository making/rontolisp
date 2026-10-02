# var

`(var name)`, `#'name`

Answers the var of a program definition: one object per name, printing `#'ns/name`.
`deref` (or `@`) reads its root and calling it calls the root, so `#'f` stands in for
`f` anywhere a function goes. [meta](meta.md) answers what the newest definition above
the site recorded: `:arglists`, the docstring as `:doc`, the name's metadata and attr
map, `:line`/`:column`/`:file`, `:name` and `:ns`. A local is no var (the name resolves
past it), and a `clojure.core` var is refused by name.

```clojure
(defn greet "Says hello." [who] (str "Hello, " who))
(println #'greet)                ; #'user/greet
(println (:doc (meta #'greet)))  ; Says hello.
(println (:arglists (meta #'greet))) ; ([who])
(println (#'greet "Ann"))        ; Hello, Ann
```
