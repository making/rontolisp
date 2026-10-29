# class

`(class x)`

Answers the value's kind as a keyword: `:map`, `:vector`, `:set`, `:list`,
`:string`, `:keyword`, `:symbol`, `:char`, `:boolean`, `:nil`,
`:function` or `:atom` (a sorted map is `:map`, a sorted set `:set`). A number answers its
class's name as a keyword: `:java.lang.Long`, `:java.lang.Double`, `:clojure.lang.Ratio`, or
`:clojure.lang.BigInt` past a long (`1N` is a `Long` and a decimal a `Ratio` here); `Long` and
`Double` in value position read as the same keyword, so `(= Long (class 1))` is `true`. A byte array answers
`:[B`, `byte[]`'s binary name. The oracle answers
host classes, which no wasm backend has -- the keyword names the kind instead, on every
backend alike. A record or deftype answers its tag keyword. An exception or a runtime error
answers its class name as a keyword (`:java.lang.IllegalArgumentException`,
`:clojure.lang.ExceptionInfo`; a runtime error or a refusal the class the oracle throws for it,
an error naming no class `:java.lang.RuntimeException`). A stream (`*out*`, `*in*`, `*err*`, a `StringWriter`, a reader) answers the host class its printer names as a keyword
(`:java.io.OutputStreamWriter`, `:java.io.StringWriter`, `:java.io.BufferedReader`, ...). A host
object answers its host class on the interpreter and the JVM, so a `class` dispatch over one reaches an `Object` or
`:default` method. `.getClass` of an exception or a stream answers the same keyword, and a
`defmethod` or a hierarchy operation (`isa?`, `derive`, `ancestors`, ...) naming the class reads it as that keyword. As a value a
one-argument lambda.

```clojure
(println (class "a") (class 1) (class 1.5)) ; :string :java.lang.Long :java.lang.Double
(println (class *out*) (class (java.io.StringWriter.))) ; :java.io.OutputStreamWriter :java.io.StringWriter
(println (class (ex-info "m" {})) (try (+ 1 "a") (catch Exception e (class e)))) ; :clojure.lang.ExceptionInfo :java.lang.ClassCastException
```
