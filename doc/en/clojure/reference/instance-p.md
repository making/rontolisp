# instance?

`(instance? Class x)`

`true` when `x` is of the class. Only the core classes lower -- `String`,
`CharSequence`, `Character`, `Boolean`, `Number`, `Long`, `Double`, `Object`,
`clojure.lang.Keyword` and `clojure.lang.Symbol` (with `java.lang.` spellings
where they have them); any other class is a named refusal instead of a wrong
answer, since no wasm backend has host widths to compare. A known record/deftype name tests the dispatch tag instead.
A throwable class (`Exception`, `IllegalArgumentException`, `clojure.lang.ExceptionInfo`, a
dotted or imported one) tests an exception or a runtime error by its class -- the class
`class` answers or a subclass of it -- and a host `Throwable` on the interpreter and the JVM
by its host class.

```clojure
(println (instance? String "a") (instance? String 1)) ; true false
(println (instance? RuntimeException (IllegalArgumentException. "x")) (instance? RuntimeException (Exception. "x"))) ; true false
```
