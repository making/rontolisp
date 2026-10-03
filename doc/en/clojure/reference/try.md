# try

`(try expr* (catch Class e expr*)* (finally expr*)?)`

Evaluates the body expressions and answers the last one. `try` guards a `handler-case` inside an
`unwind-protect`: the `finally` body runs on the way out, and the answer survives it. A catch
clause takes an exception whose class is the class it names or a subclass of it; the clauses are
tried in order, the first that takes the exception runs with its variable bound to it, and an
exception no clause takes passes on to the handlers outside, after the `finally` -- like the
oracle. The class resolves dotted, imported or as a `java.lang` default import, and must be a
`Throwable`; anything else is the oracle's refusal (`Unable to resolve classname: Foo`).

A runtime error is the Common Lisp condition the runtime signals, and a catch takes it by the
class the oracle throws there: a type error is a `ClassCastException` (a `NullPointerException`
for `nil`, an `IndexOutOfBoundsException` for an index past its bound, an
`UnsupportedOperationException` for `count` of a value that is no collection), an arithmetic
error an `ArithmeticException`, a wrong argument count a `clojure.lang.ArityException`, a failed
open a `java.io.FileNotFoundException`. A runtime error whose condition names no class is taken
by any catch but `clojure.lang.ExceptionInfo`'s ([deviations](../deviations.md)). The caught
exception is what `ex-message`/`ex-data`/`ex-cause`, `.getMessage`/`.getCause` and `str` read.

```clojure
(println (try 1 (catch Exception e 2) (finally nil))) ; 1
(println (try (throw (IllegalStateException. "bad")) (catch IllegalArgumentException e :iae) (catch IllegalStateException e :ise))) ; :ise
(println (try (+ 1 "a") (catch ArithmeticException e :arith) (catch ClassCastException e :cce))) ; :cce
(println (try (try (throw (ex-info "m" {})) (catch IllegalArgumentException e :iae)) (catch clojure.lang.ExceptionInfo e (ex-message e)))) ; m
```
