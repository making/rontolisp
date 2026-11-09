# class?

`(class? x)`

`clojure.core/class?`: `true` for a host class object (`String`, `java.io.File` as a value), which only interop answers (interpreter and JVM), and for a class value with no host object: a record's or deftype's class, what [type](type.md) answers, a class literal on the wasm backends. `class` of most Lisp values answers a kind keyword here, so `(class? (class 1))` is `false` (the oracle: `true`). As a value a one-argument function.

```clojure
(defrecord R [a])
(println (class? 1) (class? (class 1)) (class? R) (class? (class (->R 1))))  ; false false true true
```
