# java:new

`(java:new "fully.qualified.ClassName" args...)`

Constructs a host (Java) object by reflection, choosing the constructor whose
parameters best match the arguments, and returns an opaque `java` object that
prints as `#<java <class-name>>`. Part of the JVM-interpreter-only `java` interop
package — compiling a `java:` form is an error, and it needs the class to be
present and reflectable at runtime. See the [Java interop
guide](../../guides/java-interop.md).

```lisp
(java:call (java:new "java.lang.StringBuilder" "ab") "length")   ; => 2
```

A `java.lang.StringBuilder` is constructed from the string `"ab"`, then its
`length` method returns `2`.
