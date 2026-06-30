# java:field

`(java:field class-or-object "fieldName")`

Reads a field by reflection: with a class-name string it reads a static field
(such as a constant), and with a `java` object it reads that instance's field.
Returns the marshalled value. Part of the JVM-interpreter-only `java` interop
package — compiling a `java:` form is an error. See the [Java interop
guide](../../guides/java-interop.md).

```lisp
(java:field "java.lang.Integer" "MAX_VALUE")   ; => 2147483647
```

The static constant `Integer.MAX_VALUE` is read and marshalled to a rontolisp
integer.
