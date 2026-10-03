# var

`(var name)`, `#'name`

Answers the var of a program definition: one object per name, printing `#'ns/name`.
`deref` (or `@`) reads its root and calling it calls the root, so `#'f` stands in for
`f` anywhere a function goes. A declared-but-never-defined name's var reads the unbound
root ([declare](declare.md)). [meta](meta.md) answers what the newest definition above
the site recorded: `:arglists`, the docstring as `:doc`, the name's metadata and attr
map, `:line`/`:column`/`:file`, `:name` and `:ns`. A local is no var (the name resolves
past it). A `clojure.core` name is the core var, printing `#'clojure.core/name`: its root
is the core value and its metadata `:name`, `:ns` and a macro's `:macro`; a core var
with no value here (`#'*err*`) is refused.

```clojure
(defn greet "Says hello." [who] (str "Hello, " who))
(println #'greet)                ; #'user/greet
(println (:doc (meta #'greet)))  ; Says hello.
(println (:arglists (meta #'greet))) ; ([who])
(println (#'greet "Ann"))        ; Hello, Ann
(println #'inc (#'inc 1))        ; #'clojure.core/inc 2
```
