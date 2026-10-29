# Clojure: the default imports `Callable`, `BigInteger`, `BigDecimal` do not resolve

Difficulty: Low

```clojure
(println (instance? Callable (fn [])) Callable BigInteger)
;; clj 1.12.6: true java.util.concurrent.Callable java.math.BigInteger
;; rontolisp:  Error: unknown name: Callable
```

The oracle's `(ns-imports 'user)` holds, beside `java.lang`, `Callable`
(`java.util.concurrent`), `BigInteger` and `BigDecimal` (`java.math`) and `Compiler`
(`clojure.lang`). `ClojureNamespaceLowering.JAVA_LANG` holds only the `java.lang` names, read
at four sites (`resolveClass`, `isClasslike`, `importPairs`, `ClojureMacroLowering`'s syntax
quote). Turn it into a default-import map (simple name to FQN) and resolve the three through
it. `^Callable` then hints `Callable` (it hints nothing now; harmless, a fn already prefers
`Callable`). Check `BigInteger`/`BigDecimal` against the number representation
(`(instance? BigInteger 1N)` is false in the oracle; g91 is the kind work) before letting them
resolve as host classes.
