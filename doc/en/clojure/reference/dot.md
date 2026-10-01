# .

`(. receiver member args...)` `(. receiver (method args...))`

Calls a member on `receiver`: with a method symbol, the instance call `(. obj m args)`;
with a class as receiver, the static `(. Class m args)`. With no arguments --
`(. System currentTimeMillis)` -- it is the zero-argument static method when the
host class has one, else the field read, like `(Class/m)`. `(. obj -field)` and
`(. Class FIELD)` read fields. An instance call whose receiver is a construction
literal -- `(.isEmpty (java.util.ArrayList.))` -- a `let`/`if-let`/`when-let` local bound to one,
or a `..` step's declared return -- `(.. (java.util.ArrayList. [1]) (subList 0 1) (isEmpty))` --
and whose overloads at that arity all answer a primitive boolean, answers
`true`/`false` like the oracle. Any other host boolean keeps the shared `java:`
unmarshal and prints `nil` for `false`. The receiver decides the path: a string takes the mapped
core operation (a Lisp string is no host object), anything else goes to `java:call`.
Classes resolve dotted as written, through `:import`, or through `java.lang`. Runs on the
interpreter and the JVM only -- the wasm backends reject `java:`.

```clojure
(println (. "hi" length)) ; 2
(println (.toUpperCase "hi")) ; HI
(println (Integer/parseInt "42")) ; 42
(println (.isEmpty (java.util.ArrayList.))) ; true
(println (.contains (java.util.ArrayList. [1]) 2)) ; false
```
