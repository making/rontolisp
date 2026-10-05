# d19. Clojure: `future?`, `future-done?` and `future-cancelled?` ignore a host `java.util.concurrent.Future`

Difficulty: Low

`future?` answers `false` for every value and `future-done?`/`future-cancelled?` are refused
by name, but a host `Future` exists through interop (measured against clj 1.12.6):

```clojure
(import '(java.util.concurrent CompletableFuture))
(def done (CompletableFuture/completedFuture 1))
(println (future? done) (future? 1) (future-done? done) (future-done? (CompletableFuture.))
         (future-cancelled? (doto (CompletableFuture.) (.cancel true))))   ; true false true false true
(println (class (try (future-done? 1) (catch Throwable e e))))             ; java.lang.ClassCastException
(println (class (try (future-cancel done) (catch Throwable e e))))         ; java.lang.Boolean
```

`future?` is `(progn x false)` ("chunked-seq? ... `future?` ... no value of that kind exists" in
`.kb/clojure-frontend.md`), which a host `Future` falsifies. Make `future?` a host arm
(`%clojure-host-object-p` of `java.util.concurrent.Future`, folded away by a program naming no
`java:` operator, which must compile to the same bytes), and `future-done?`/`future-cancelled?`/
`future-cancel` host arms over `isDone`/`isCancelled`/`cancel(true)` with the oracle's
`ClassCastException` for anything else. `realized?` of a host `Future` is a `ClassCastException`
there already. Pin oracle-identical in `ClojureInteropTest`, next to the `deref` pin
(`derefOfAHostFutureIsItsGetWithAnOptionalTimeout`).
