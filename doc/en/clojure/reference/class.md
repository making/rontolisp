# class

`(class x)`

Answers the value's class, as the oracle prints and compares it: `java.lang.String`,
`java.lang.Long`, `java.lang.Double`, `java.lang.Character`, `java.lang.Boolean`,
`clojure.lang.Keyword`, `clojure.lang.Symbol`, `clojure.lang.PersistentVector`,
`clojure.lang.PersistentArrayMap` (past eight entries `clojure.lang.PersistentHashMap`),
`clojure.lang.PersistentHashSet`, `clojure.lang.PersistentTreeMap`, `clojure.lang.PersistentList`,
`clojure.lang.LazySeq`, `clojure.lang.AFunction`, `clojure.lang.Atom` ...; nil answers nil.
`(= (class "a") String)` and `(class? (class 1))` are `true`.

On the interpreter and in a JVM program that uses Java interop the class is a `java.lang.Class`:
a JDK class is the host's own, and a `clojure.lang` class is one defined with the oracle's name,
modifiers, superclass and interfaces, so `.getName`, `.isInterface`, `.isAssignableFrom`,
`Class/forName` and a Java method taking a class answer the oracle's
(`(.isAssignableFrom java.util.List (class []))` is `true`). Its declared members are the
statics this front end answers (`clojure.lang.PersistentArrayMap/createAsIfByAssoc`, ...).
Elsewhere (the wasm backends, a JVM program without interop, the native binary) it is a class
value that prints, compares, hashes and answers `class?` the same way.

A record or deftype answers its class, the value its name evaluates to (`user.R`, `str`
`"class user.R"`). An exception or a runtime error answers its class
(`clojure.lang.ExceptionInfo`, `java.lang.IllegalArgumentException`; a runtime error or a
refusal the class the oracle throws for it, an error naming no class
`java.lang.RuntimeException`), a stream the class its printer names
(`java.io.OutputStreamWriter`, `java.io.StringWriter`, ...), a host object its host class
(interpreter and JVM). As a value a one-argument lambda.

Deviations: `1N` is a `Long` and a decimal a `Ratio` here; a narrowing cast answers a `Long`;
every function is a `clojure.lang.AFunction` (the oracle compiles a class per function, extending
it), every seq that is no lazy seq a `clojure.lang.PersistentList`, a two-member vector a
`PersistentVector` (the oracle's map entry is a `MapEntry`), a typed array a `PersistentVector`;
a byte array's class prints as its binary name `[B` (the oracle: `byte/1`).

```clojure
(println (class "a") (class 1) (class [1]) (class nil)) ; java.lang.String java.lang.Long clojure.lang.PersistentVector nil
(println (= (class "a") String) (class? (class 1))) ; true true
(println (class (ex-info "m" {})) (try (+ 1 "a") (catch Exception e (class e)))) ; clojure.lang.ExceptionInfo java.lang.ClassCastException
```
