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

`object` may also be a Lisp string, number, character or `t`, called as the Java object it
becomes for an `Object` parameter (a string as a `String`, `42` as an `Integer`):

```lisp
(java:call "abc" "codePointAt" 0)
; => 97
```

The method name may carry the parameter types (`"append(CharSequence)"`). A call whose
receiver class is known from the text -- `(java:new ...)`, a declared return type,
`(the (java:object "C") x)` or `(declare (type (java:object "C") v))` -- is resolved once,
before it runs, among the methods of that class: to one method when the argument kinds are
known too, otherwise to the overloads it chooses among by the kinds its arguments have when
it runs (the guide's [Resolving calls before they
run](../../guides/java-interop.md#resolving-calls-before-they-run)).

A function argument passed where an interface is expected becomes a `java:proxy` of it, called with the method name first. Ending the call in `:functional`, after the arguments, makes it implement each abstract method by the method's arguments instead, as `java:new` and `java:static` do with the same ending (the guide's [Callbacks via java:proxy](../../guides/java-interop.md#callbacks-via-javaproxy)).

Ending the call in `:java-false`, after the arguments (before or after `:functional`),
answers Java's false as `|false|` rather than `nil` (the guide's [Java's false
back](../../guides/java-interop.md#javas-false-back-java-false)):

```lisp
(java:call (java:new "java.util.ArrayList" (list 1)) "isEmpty" :java-false)
; => |false|
```

Ending the call in `:octets`, beside the other markers, answers a `byte[]` -- the result, or
an element of an array it answers -- as an `(unsigned-byte 8)` vector of its octets rather
than a list of signed bytes, and hands one to a function the call converts the same way, what
the function stores going back into Java's array (the guide's [Octets back](../../guides/java-interop.md#octets-back-octets)):

```lisp
(let ((o (java:new "java.io.ByteArrayOutputStream")))
  (java:call o "write" 200)
  (list (java:call o "toByteArray") (java:call o "toByteArray" :octets)))
; => ((-56) #(200))
```
