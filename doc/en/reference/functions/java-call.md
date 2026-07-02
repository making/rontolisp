# java:call

`(java:call object "methodName" args...)`

Invokes an instance method on a `java` object by reflection, choosing the
overload whose parameters best match the arguments, and returns the marshalled
result (a `void` method returns `nil`). Part of the JVM-only `java` interop
package — available on the interpreter and in JVM-compiled classes, not on the
WASM backend. See the [Java interop
guide](../../guides/java-interop.md).

```lisp
(let ((lst (java:new "java.util.ArrayList")))
  (java:call lst "add" 7)
  (java:call lst "size"))
; => 1
```

A `java.util.ArrayList` is created, one element is added, and `size` returns the
element count.
