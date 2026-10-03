# c65. Clojure: a multimethod cannot dispatch on an exception class, and `.getClass` of an exception is refused

Difficulty: Medium

Since 2026-10-03 `class` of an exception or a runtime error answers its class name as a
keyword (`:java.lang.IllegalArgumentException`, `.kb/clojure-frontend.md`, "Catching"), but
nothing else reads that keyword as a class. Measured against clj 1.12.6:

```clojure
(defmulti handle class)
(defmethod handle IllegalArgumentException [e] :iae)
(defmethod handle Exception [e] :exception)
(defmethod handle :default [e] :default)
(handle (NumberFormatException. "x"))             ; oracle :iae (the nearest superclass)
(handle (IllegalStateException. "x"))             ; oracle :exception
(isa? (class (NumberFormatException. "x")) IllegalArgumentException) ; oracle true
(.getClass (Exception. "x"))                      ; oracle java.lang.Exception
```

Here the `defmethod` is refused (`defmethod needs a core class, not IllegalArgumentException`,
`ClojureDispatchLowering.dispatchClassKey`), `isa?` sees two unrelated values, and `.getClass`
of a condition is a `java:call` refusal (interpreter, JVM) or a call-time error (wasm). A
throwable class spelling as a dispatch value would lower to the keyword `class` answers, and
the dispatcher's hierarchy step would need the class chain (`ClojureThrowables`) for
`isa?` between class keywords -- the oracle's `isa?` follows Java inheritance, the most
specific method winning. `.getClass` stays out of `%clojure-exception-method` for size: routing
it there splices the exception runtime into every program calling `.getClass` on a value of
unknown class; it needs the reader only where a condition can reach it, like `class`'s arm.
All four backends; pin in clojure-spec.
