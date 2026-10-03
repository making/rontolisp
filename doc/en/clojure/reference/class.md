# class

`(class x)`

Answers the value's kind as a keyword: `:map`, `:vector`, `:set`, `:list`,
`:string`, `:number`, `:keyword`, `:symbol`, `:char`, `:boolean`, `:nil`,
`:function` or `:atom`. The oracle answers host classes, which no wasm backend
has -- the keyword names the kind instead, on every backend alike. A record or deftype answers its tag keyword. A host
object answers its host class on the interpreter and the JVM, so a `class` dispatch over one reaches an `Object` or
`:default` method. As a value a one-argument lambda.

```clojure
(println (class "a") (class 1)) ; :string :number
```
