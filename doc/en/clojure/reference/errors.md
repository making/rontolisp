# Errors

`try` guards a `handler-case` inside an `unwind-protect`. A catch clause takes an exception of the class it names or a subclass, the clauses in order, and the catch variable binds the exception; a runtime error is taken by the class the oracle throws for it ([try](try.md)). An exception is a condition on every backend: an `ex-info`, a construction of a throwable class that carries only a message and a cause (`(Exception. "m")`, `(IllegalArgumentException. "m" cause)`), a thrown host `Throwable`, or the Common Lisp condition of a runtime error.

| Name | Example | Result |
|---|---|---|
| `try` | `(try 1 (catch Exception e 2) (finally nil))` | `1` |
| `throw` | `(try (throw (ex-info "boom" {:code 42})) (catch Exception e (get (ex-data e) :code)))` | `42` |
| `ex-info` | `(ex-message (ex-info "boom" {}))` | `boom` |
| `ex-data` | `(ex-data (ex-info "boom" {:code 42}))` | `{:code 42}` |
| `ex-message` | `(ex-message (ex-info "boom" {}))` | `boom` |
| `ex-cause` | `(ex-message (ex-cause (ex-info "a" {} (Exception. "c"))))` | `c` |
| `.getMessage` | `(.getMessage (Exception. "boom"))` | `boom` |
| `assert` | `(assert (= 1 1))` | `nil` |
