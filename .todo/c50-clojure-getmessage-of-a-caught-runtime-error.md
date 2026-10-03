# c50. Clojure `(.getMessage e)` of a caught runtime error is refused

Difficulty: Medium

`(try (/ 1 0) (catch ArithmeticException e (.getMessage e)))` (2026-10-03): the interpreter
and the JVM signal `java:call expects a java object as the first argument, got
#<DIVISION-BY-ZERO ...>`; wasm and the component fail on `JAVA:CALL` (no host objects). The
oracle (`clj` 1.12.6) answers `"Divide by zero"`. Any runtime error a program catches (an
undefined function, a type error, a `(error ...)` from the runtime) is a CL condition, so
`.getMessage` -- the most common way Clojure code reads a caught exception -- never works on
one; `ex-message` does.

b66 covers a thrown host `Throwable`; this is the other half: a method call whose receiver is
a CL condition. Plan: lower `.getMessage` (and `.getLocalizedMessage`, `.toString`?) on a
receiver that may be a condition through a runtime helper answering the condition's report
text on all four backends, the host call otherwise; pin in clojure-spec.
