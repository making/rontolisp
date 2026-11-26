# keyword

`(keyword x)` / `(keyword ns nm)`

One argument: a keyword itself, a symbol's demangled spelling, a string verbatim
(`a/b` stays whole) -- `nil` for anything else. Two arguments: the slash-joined
spelling (a `nil` namespace drops, a `nil` name signals). As a value a
rest-dispatch lambda over both shapes.

```clojure
(println (keyword "a" "b")) ; :a/b
(println (keyword 'a)) ; :a
```
