# java:subclass

`(java:subclass "fully.qualified.Superclass" '("iface"...) '("method"...) constructor-args... callable)`

Creates a host instance extending the superclass (and the extra interfaces) --
backed by a rontolisp callable. A `java:proxy` cannot extend a class (a
`java.lang.reflect.Proxy` implements interfaces only), so a class proxy is a
generated subclass instead, on the interpreter (defined when it runs) as in a
compiled program (generated at compile time).

Every named method is dispatched to the callable as `(callable this
"method-name" arg...)` -- so the callable's **first argument is the proxy object
itself** (`this`), the second the name of the invoked method (a string), and the
rest the method's arguments. The callable's return value is marshalled back to
the method's return type (a `void` method ignores it; a function returned is not
made a proxy -- return a `java:reify` or `java:proxy` object where an interface
is expected). This is how a Clojure `proxy` over a class is written; a
`proxy-super` reaches the superclass implementation through the generated
`super$` accessor, called as an ordinary method:

```lisp
(let ((f (java:subclass "java.io.File" '() '("lastModified") "recent"
            (lambda (this method &rest args) 42))))
  (java:call f "lastModified"))
; => 42
```

The constructor arguments choose the superclass constructor by the shared
overload rule, public and protected constructors included. A named method runs
its body -- `toString`/`equals`/`hashCode` included, unlike `java:proxy`, which
keeps `Object`'s three. A method left out is inherited when the class chain
implements it, and throws `UnsupportedOperationException` with the method's name
when it is called and nothing implements it:

```lisp
(let ((f (java:subclass "java.io.File" '() '("toString") "x"
            (lambda (this method &rest args) "over!"))))
  (list (java:call f "toString") (java:call f "getName") (java:call f "super$toString$0")))
; => ("over!" "x" "x")
```

Ending the form in `:functional`, after the callable, makes a function constructor
argument passed where an interface is expected implement it by the method's arguments
instead of as a `java:proxy` (`java:new`'s `:functional`). Ending it in `:java-false` hands
the callable Java's false as `|false|` rather than `nil` (the guide's [Java's false
back](../../guides/java-interop.md#javas-false-back-java-false)), and in `:octets` a `byte[]`
as an `(unsigned-byte 8)` vector of its octets -- Java's own array, so what the callable
stores Java reads (the guide's [Octets back](../../guides/java-interop.md#octets-back-octets)).

## In a compiled program

A `java:subclass` whose superclass, interfaces and methods are literal strings
the compile can see is a class generated at compile time (`Prog$Subclass0.class`
beside the program): no reflection, so the construction compiles under
`--java-static` (a call on the object itself is still resolved by its class when
it runs). A `java:subclass` left to run time -- a name computed at run time, or
a class the compile cannot see -- is refused by name (the bridge generates no
classes); the interpreter resolves it when it runs. See the [Java interop
guide](../../guides/java-interop.md).
