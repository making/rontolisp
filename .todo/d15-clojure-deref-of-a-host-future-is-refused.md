# d15. Clojure: `deref` of a host `java.util.concurrent.Future` is refused

Difficulty: Low

`future` and `promise` are refused by name, but a host `Future` exists through interop, and
the oracle derefs it (measured 2026-10-05 against clj 1.12.6):

```clojure
(println @(java.util.concurrent.CompletableFuture/completedFuture 1))   ; 1
(println (try @(java.util.concurrent.CompletableFuture/failedFuture (Exception. "x"))
              (catch java.util.concurrent.ExecutionException e (.getMessage (.getCause e)))))  ; x
(println (deref (java.util.concurrent.CompletableFuture.) 10 :timeout))  ; :timeout
```

On the interpreter and the JVM the first two signal `ClassCastException: deref needs an atom`;
the three-argument `deref` is refused at lower time (`deref takes one argument`). A host
`Future` is `.get` (the timeout arity `.get` with `TimeUnit/MILLISECONDS`, the default on
`TimeoutException`), as a host arm, so a program naming no `java:` operator compiles to the
same bytes. Pin oracle-identical in `ClojureInteropTest`.
