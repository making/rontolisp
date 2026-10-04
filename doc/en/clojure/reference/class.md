# class

`(class x)`

Answers the value's kind as a keyword: `:map`, `:vector`, `:set`, `:list`,
`:string`, `:number`, `:keyword`, `:symbol`, `:char`, `:boolean`, `:nil`,
`:function` or `:atom` (a sorted map is `:map`, a sorted set `:set`). The oracle answers
host classes, which no wasm backend has -- the keyword names the kind instead, on every
backend alike. A record or deftype answers its tag keyword. An exception or a runtime error
answers its class name as a keyword (`:java.lang.IllegalArgumentException`,
`:clojure.lang.ExceptionInfo`; a runtime error the class the oracle throws for it, a refusal whose
condition names no class `:java.lang.RuntimeException`). A stream (`*out*`, `*in*`, `*err*`, a `StringWriter`, a reader) answers the host class its printer names as a keyword
(`:java.io.OutputStreamWriter`, `:java.io.StringWriter`, `:java.io.BufferedReader`, ...). A host
object answers its host class on the interpreter and the JVM, so a `class` dispatch over one reaches an `Object` or
`:default` method. As a value a one-argument lambda.

```clojure
(println (class "a") (class 1)) ; :string :number
(println (class *out*) (class (java.io.StringWriter.))) ; :java.io.OutputStreamWriter :java.io.StringWriter
(println (class (ex-info "m" {})) (try (+ 1 "a") (catch Exception e (class e)))) ; :clojure.lang.ExceptionInfo :java.lang.ClassCastException
```
