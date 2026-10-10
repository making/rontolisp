# java:field

`(java:field class-or-object "fieldName")`

Reads a field: with a class-name string it reads a static field (such as a
constant) -- an instance field named that way is an error -- and with a `java`
object it reads that instance's field. Returns the marshalled value. Part of the JVM-only `java` interop
package — available on the interpreter and in JVM-compiled classes, not on the
WASM backend. See the [Java interop
guide](../../guides/java-interop.md).

```lisp
(java:field "java.lang.Integer" "MAX_VALUE")   ; => 2147483647
```

The static constant `Integer.MAX_VALUE` is read and marshalled to a rontolisp
integer.

Ending the form in `:java-false`, after the field name, answers Java's false as `|false|`
rather than `nil` (the guide's [Java's false back](../../guides/java-interop.md#javas-false-back-java-false)):

```lisp
(java:field "java.lang.Boolean" "FALSE" :java-false)   ; => |false|
```

Ending it in `:octets` answers a `byte[]` field as an `(unsigned-byte 8)` vector of its
octets (the guide's [Octets back](../../guides/java-interop.md#octets-back-octets)).
