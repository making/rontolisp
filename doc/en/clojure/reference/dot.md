# .

`(. receiver member args...)` `(. receiver (method args...))`

Calls a member on `receiver`: with a method symbol, the instance call `(. obj m args)`;
with a class as receiver, the static `(. Class m args)`. With no arguments --
`(. System currentTimeMillis)` -- it is the zero-argument static method when the
host class has one, else the field read, like `(Class/m)`. `(. obj -field)` and
`(. Class FIELD)` read fields. Java's `false` comes back as `false`, a boolean answer and a
`Boolean.FALSE` read from a host collection alike, and a keyword, symbol, set or map passed
as an argument reaches Java as a value of its own ([Deviations](../deviations.md) has what
differs). The receiver decides the path: a string takes the mapped
core operation, which runs on every backend; anything else goes to `java:call`, which calls a
string as a `String`, a number as its box and a character as a `Character`
(`(.codePointAt "abc" 0)`, `(.compareTo 1 2)`).
Classes resolve dotted as written, through `:import`, or through `java.lang`. Runs on the
interpreter and the JVM only -- the wasm backends reject `java:`.

```clojure
(println (. "hi" length)) ; 2
(println (.toUpperCase "hi")) ; HI
(println (.codePointAt "abc" 0)) ; 97
(println (Integer/parseInt "42")) ; 42
(println (.isEmpty (java.util.ArrayList.))) ; true
(println (.contains (java.util.ArrayList. [1]) 2)) ; false
```
