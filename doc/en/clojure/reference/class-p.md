# class?

`(class? x)`

`clojure.core/class?`: `true` for a host class object (`String`, `java.io.File` as a value), which only interop answers (interpreter and JVM). `class` of a Lisp value answers a kind keyword here, so `(class? (class 1))` is `false` (the oracle: `true`). As a value a one-argument function.

```clojure
(println (class? 1) (class? (class 1)))  ; false false
```
