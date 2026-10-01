# with-meta

`(with-meta obj metadata)`

Answers the object: metadata (`^:private`, `^:dynamic`, `^{...}` attr maps, type
hints) parses and drops everywhere, since it never affects dispatch. Only
`binding` reads one piece of it (`^:dynamic` marks rebindable vars).

```clojure
(println (with-meta [1 2] {:tag :x})) ; [1 2]
```
