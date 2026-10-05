# java Package Functions

The `java` package drives arbitrary Java APIs. It is
**JVM-only** — it works on the interpreter (`java -jar rontolisp.jar`) and in
JVM-compiled classes (a call resolved at compile time becomes a direct call, a
`java:reify`, `java:proxy` or `java:subclass` a class generated for it, and the others go through
a reflection bridge the compiler writes beside the generated `.class`), but not
on the WASM backend, and the GraalVM native binary
carries no reflection metadata to interpret it — and **not part of Common
Lisp**; reference its functions with the `java:`
qualifier. Each name below links to its own page; the [Java interop
guide](../../guides/java-interop.md) covers marshalling, overload resolution and
limitations.

| Function | Example | Result |
|----------|---------|--------|
| `java:new` | `(java:new "java.lang.StringBuilder" "ab")` | a host object (`#<java ...>`) |
| `java:call` | `(java:call obj "size")` | the marshalled instance-method result |
| `java:static` | `(java:static "java.lang.Math" "max" 3 7)` | the marshalled static-method result |
| `java:field` | `(java:field "java.lang.Integer" "MAX_VALUE")` | the marshalled field value |
| `java:proxy` | `(java:proxy "java.lang.Runnable" (lambda (m) ...))` | an interface instance backed by the callable |
| `java:subclass` | `(java:subclass "java.io.File" '() '("lastModified") "x" (lambda (this m) ...))` | a class instance backed by the callable |
| `java:reify` | `(java:reify "java.lang.Runnable" "run" (lambda () ...))` | an interface instance with one function per method |
| `java:java-exception-cause` | `(java:java-exception-cause e)` | the exception a `java:java-exception` carries |

A member that throws signals the condition type `java:java-exception`, a `simple-error`
carrying the exception (the guide's [Errors and non-local
exits](../../guides/java-interop.md#errors-and-non-local-exits)). Two more symbols serve
resolution before a call runs: the type specifier
`(java:object "fqcn")`, for `the` and `declare`, and the variable
`java:*warn-on-reflection*`, which reports the calls left to run time (the guide's
[Resolving calls before they run](../../guides/java-interop.md#resolving-calls-before-they-run)).

