# java:reify

`(java:reify "fully.qualified.Interface" "method" function ...)`

`(java:reify '("Interface" ...) [:value value] [:class "class"] "method" function ...)`

Creates a host instance of the given interface whose methods are implemented one by one.
Each `"method"` names one method of the interface, and the `function` after it is called
with that method's arguments -- unlike [`java:proxy`](java-proxy.md), without the method's
name. The function's value is converted to the method's return type (a `void` method
ignores it). Part of the JVM-only `java` interop package: the interpreter and JVM-compiled
classes, not the WASM backend. See the [Java interop
guide](../../guides/java-interop.md#implementing-interfaces-with-javareify).

```lisp
(java:call (java:reify "java.util.function.Function" "apply" (lambda (x) (* x 10))) "apply" 4)
; => 40
```

## Naming the method

A name several methods of the interface share is tagged with the parameter types, as a
`java:call` method name is: `"append(char)"`, `"append(CharSequence,int,int)"`. A name that
matches more than one method, or none, is an error.

```lisp
(let* ((seen nil)
       (out (java:reify "java.lang.Appendable"
              "append(char)" (lambda (c) (push c seen) nil)
              "append(CharSequence)" (lambda (s) (push s seen) nil)
              "append(CharSequence,int,int)" (lambda (s start end) (push (subseq s start end) seen) nil))))
  (java:call out "append" #\a)
  (java:call out "append" "bc")
  (reverse seen))
; => (#\a "bc")
```

`toString`, `equals` and `hashCode` can be named too. Otherwise `equals` and `hashCode` are
identity, and `toString` answers `#<java-reify fully.qualified.Interface>`.

## Methods no name designates

An abstract method is not implemented: calling it throws `UnsupportedOperationException`
(`java:reify: no implementation of java.util.Iterator.next()`). A default method keeps the
interface's body, so combinators work:

```lisp
(let ((times-ten (java:reify "java.util.function.Function" "apply" (lambda (x) (* x 10))))
      (plus-one (java:reify "java.util.function.Function" "apply" (lambda (x) (+ x 1)))))
  (java:call (java:call times-ten "andThen" plus-one) "apply" 4))
; => 41
```

## Several interfaces

A list of interface names makes one object implementing each of them, so Java can hold it
as any of them. A name designates a method of any of the interfaces; a method two of them
declare with one parameter list is one method. Each name must be an interface, named once
(`java:reify names interface I twice`), and one that another listed interface extends adds
nothing. The object's `toString`, unless named, is `#<java-reify I J>`.

```lisp
(let* ((ran nil)
       (o (java:reify '("java.lang.Runnable" "java.util.function.Supplier")
            "run" (lambda () (setq ran t))
            "get" (lambda () 42))))
  (java:call (java:new "java.lang.Thread" o) "run")
  (list ran (java:call o "get")))
; => (T 42)
```

## Standing for a value

`:value` right after the interfaces makes the object stand for a Lisp value, as a
[`java:handle`](java-handle.md) does: wherever Java hands the object back -- a method's
result, an array's element, a callback's argument -- `java:` answers the value. `:class`
names the class Java's messages give the object; without it, its own.

```lisp
(let* ((cell (list :cell 1))
       (l (java:new "java.util.ArrayList")))
  (java:call l "add" (java:reify "java.lang.Runnable" :value cell :class "my.Cell" "run" (lambda () nil)))
  (eq (java:call l "get" 0) cell))
; => T
```

Its `equals` and `hashCode`, unless named, are those of a handle with a nil hash: equal only
to an object standing for the very same value under the same class, its hash the value's
identity hash. Its `toString`, unless named, is `Object`'s spelling of the class and that
hash, such as `my.Cell@1b6d3586`. An interface that declares `value()` or `className()` --
the methods through which the object answers the value and the class -- cannot be
implemented so (`java:reify: :value conflicts with I.value()`).

## The value a function returns

It is converted as an argument is: an integer to `int`, a list to an array or a `List`,
`nil` to `false` or `null`, and so on. A function is not made a proxy on the way back --
return a `java:reify` or `java:proxy` object where an interface is expected. A value that
does not convert is an error, for example `java:reify: cannot return "x" as int from
java.util.function.IntSupplier.getAsInt`.

Java's false reaches a function as `nil`. Ending the form in `:java-false`, after the last
function, hands it `|false|` instead (the guide's [Java's false
back](../../guides/java-interop.md#javas-false-back-java-false)):

```lisp
(let ((seen nil))
  (java:call (java:reify "java.util.function.Consumer" "accept" (lambda (x) (push x seen)) :java-false)
             "accept" '|false|)
  seen)
; => (|false|)
```

## In a compiled program

A `java:reify` whose interface and method names are literal strings (a list of them quoted,
a `:class` literal too), and whose interfaces the compile can see, is a class generated at
compile time (`Prog$Reify0.class` beside the program). Nothing reflects, so the program
compiles under `--java-static` and builds into a GraalVM native image with no
configuration. The object prints as `#<java Prog$Reify0>` compiled and as the name of a
`java.lang.reflect.Proxy` class interpreted. A `java:reify` whose names are computed is
implemented when it runs, through the reflection bridge.
