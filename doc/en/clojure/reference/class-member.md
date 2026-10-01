# Class/member

`(Class/member args...)` `(Class/FIELD)`

Calls the static method, or with a zero-argument position reads the static field -- a
zero-argument static *method* spells `(. Class m)` instead, since the bare form reads a
field there. The class resolves dotted, imported, or `java.lang`. Runs on the interpreter
and the JVM only -- the wasm backends reject `java:`.

```clojure
(println (Integer/parseInt "42")) ; 42
```
