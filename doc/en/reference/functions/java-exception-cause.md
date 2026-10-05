# java:java-exception-cause

`(java:java-exception-cause condition)`

Answers the exception a `java:java-exception` carries: the `java.lang.Throwable` a
`java:` member threw. A `java:java-exception` is the condition such a member signals, a
`simple-error` whose report names the member and the exception; anything else is a
`type-error`. Part of the JVM-only `java` interop package -- available on the interpreter
and in JVM-compiled classes, not on the WASM backend. See the [Java interop
guide](../../guides/java-interop.md#errors-and-non-local-exits).

```lisp
(handler-case (java:static "java.lang.Integer" "parseInt" "x")
  (java:java-exception (e)
    (java:call (java:call (java:java-exception-cause e) "getClass") "getName")))
; => "java.lang.NumberFormatException"
```

`Integer.parseInt` throws a `NumberFormatException`; the handler reads its class off the
exception the condition carries.

An exception that a loaded Clojure file calling Java throws is a `java:java-exception` too.
Its cause is a host exception of the exception's class, built once from its message and
cause (an `ex-info`'s is a `RuntimeException`).
