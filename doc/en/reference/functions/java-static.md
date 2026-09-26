# java:static

`(java:static "fully.qualified.ClassName" "methodName" args...)`

Invokes a static method by reflection, choosing the overload whose parameters
best match the arguments, and returns the marshalled result. Part of the
JVM-only `java` interop
package — available on the interpreter and in JVM-compiled classes, not on the
WASM backend.
See the [Java interop guide](../../guides/java-interop.md).

```lisp
(java:static "java.lang.Math" "max" 3 7)   ; => 7
```

`Math.max` is overloaded for `int`/`long`/`float`/`double`; the integer
arguments select the `int` overload, so the result is the integer `7`.

The method name may carry the parameter types, which picks the overload directly:
`"max(long,long)"`, or `"max(long,_)"` with `_` left to the cost rule. A call whose argument
kinds are known from the text is resolved once, before it runs (the guide's [Resolving
calls before they run](../../guides/java-interop.md#resolving-calls-before-they-run)).
