# c64. Clojure: a host exception loses its class crossing the `java:` boundary, and an exception cannot cross back

Difficulty: High

Interpreter and JVM, measured 2026-10-03 against clj 1.12.6.

A host call that throws (`(Integer/parseInt "x")`, `(Class/forName "no.Such")`,
`(Thread/sleep -1)`) reaches the program as a `simple-error` whose message is
`error calling java.lang.Integer.parseInt: java.lang.NumberFormatException: For input
string: "x"` (`eval/JavaInterop.fail`, the JVM's `_jfail`): the throwable is reduced to text.
So a `catch` cannot see its class -- it is taken by any catch but `ExceptionInfo`'s
(`.kb/clojure-frontend.md`, "Catching"), a `RuntimeException` catch takes a
`ClassNotFoundException` the oracle passes on -- and `.getMessage`/`ex-message` answer that
text where the oracle answers `For input string: "x"`. The fix belongs at the boundary: the
condition carries the throwable (a host object), so `%clojure-exception-of` converts it like a
thrown host `Throwable` (class chain from `getClass`, message, cause). On the JVM the failure
is a `RuntimeException` built in `_jfail`; the condition would have to travel under it like
`_condTl`'s ("The JVM keeps what a throwable carries under the throwable").

The other direction: an exception condition (`(java.io.IOException. "io")` is one since
2026-10-03) passed where a host member takes a `Throwable` finds no member:
`(java.io.UncheckedIOException. "u" (java.io.IOException. "io"))` is `No matching constructor
for java.io.UncheckedIOException with 2 argument(s)` (the oracle builds it, cause `io`).
Converting a condition to a host `Throwable` at the boundary (its class, message and cause)
would answer both.
