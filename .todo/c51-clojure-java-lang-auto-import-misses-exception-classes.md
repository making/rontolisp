# c51. Clojure: the `java.lang` auto-import misses the common exception classes

Difficulty: Low

`(throw (IllegalStateException. "boo"))` and `(IllegalArgumentException. "boo")` (2026-10-03):
the interpreter and the JVM signal `No such class: IllegalStateException`, wasm `JAVA:NEW is
undefined`. The oracle (`clj` 1.12.6) imports all of `java.lang`, so `(ex-message e)` answers
`"boo"`. `ClojureNamespaceLowering.JAVA_LANG` lists only `Exception`, `RuntimeException`,
`Error` among the throwables; `IllegalStateException`, `IllegalArgumentException`,
`ArithmeticException`, `UnsupportedOperationException`, `IndexOutOfBoundsException`,
`NullPointerException`, `ClassCastException`, `Throwable` are missing (a `catch` of one
resolves by name only, so it is the construction that fails).

Plan: complete the list (or resolve any `java.lang` class the host has) and decide what a
construction of one answers on wasm, where `Exception.` already lowers (check how); pin in
clojure-spec and `ClojureInteropTest`.
