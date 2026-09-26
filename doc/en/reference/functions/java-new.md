# java:new

`(java:new "fully.qualified.ClassName" args...)`

Constructs a host (Java) object by reflection, choosing the constructor whose
parameters best match the arguments, and returns an opaque `java` object that
prints as `#<java <class-name>>`. Part of the JVM-only `java` interop
package — available on the interpreter and in JVM-compiled classes, not on the
WASM backend, and it needs the class to be
present and reflectable at runtime. See the [Java interop
guide](../../guides/java-interop.md).

```lisp
(java:call (java:new "java.lang.StringBuilder" "ab") "length")   ; => 2
```

A `java.lang.StringBuilder` is constructed from the string `"ab"`, then its
`length` method returns `2`.

The class name may carry the constructor's parameter types, which picks it directly:
`(java:new "java.lang.StringBuilder(int)" 64)`. A call whose argument kinds are known
from the text is resolved once, before it runs (the guide's [Resolving calls before they
run](../../guides/java-interop.md#resolving-calls-before-they-run)).
