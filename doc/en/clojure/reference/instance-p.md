# instance?

`(instance? Class x)`

`true` when `x` is of the class. Only the core classes lower -- `String`,
`CharSequence`, `Character`, `Boolean`, `Number`, `Long`, `Double`, `Object`,
`clojure.lang.Keyword` and `clojure.lang.Symbol` (with `java.lang.` spellings
where they have them); any other class is a named refusal instead of a wrong
answer, since no wasm backend has host widths to compare. A known record/deftype name tests the dispatch tag instead.

```clojure
(println (instance? String "a") (instance? String 1)) ; true false
```
