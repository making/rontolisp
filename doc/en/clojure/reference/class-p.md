# class?

`(class? x)`

`clojure.core/class?`: `true` for a class value -- what [class](class.md) and
[type](type.md) answer, a class literal (`String`, `java.io.File`, `clojure.lang.IFn` as a
value), a record's or deftype's class -- like the oracle's `(instance? Class x)`. As a value a
one-argument function.

```clojure
(defrecord R [a])
(println (class? 1) (class? (class 1)) (class? R) (class? (class (->R 1))))  ; false true true true
```
