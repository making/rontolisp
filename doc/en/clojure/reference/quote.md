# quote

`(quote form)` `'form`

Answers the form unevaluated. Symbols travel mangled behind the `c%` prefix and
demangle again on print, so a quoted symbol prints as it was read. A quoted vector
re-emits as a `vector` call, and a quoted map or set is the construction over the
quoted elements -- all three build their real collection.

```clojure
(println 'qi-a)         ; qi-a
(println '(1 2 qi-b))   ; (1 2 qi-b)
(println '[1 :a])       ; [1 :a]
(println (get '{:a 1} :a)) ; 1
```
