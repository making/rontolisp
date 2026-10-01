# symbol

`(symbol x)` / `(symbol ns nm)`

One argument: itself for a symbol, the spelled symbol (mangled behind the
prefix, so it prints and compares whole) for a keyword or a string -- anything
else signals. Two arguments: the slash-joined spelling (a `nil` namespace is the
one-argument shape). As a value a rest-dispatch lambda over both shapes.

```clojure
(println (symbol "a" "b")) ; a/b
(println (= (symbol "a") 'a)) ; true
```
