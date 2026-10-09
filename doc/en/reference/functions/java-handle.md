# java:handle

`(java:handle value text &optional hash order class)`

Makes a Java object that stands for `value`, a Lisp value Java has no value of (a symbol, a
list, ...). Java sees `text` as the object's `toString`. Two handles of one `class` and one
text are `equals`; the object's `hashCode` is `hash`'s low 32 bits (the text's `hashCode`
without one), and a handle orders a handle of its class by `order` (the text without one),
so a handle keys a `java.util.HashMap` and sorts in a `java.util.TreeSet`. Wherever Java
hands the handle back -- a method's result, an array's element, a callback's argument --
`java:` answers `value`. Part of the JVM-only `java` interop package — available on the
interpreter and in JVM-compiled classes, not on the WASM backend. See the [Java interop
guide](../../guides/java-interop.md#handles-javahandle).

```lisp
(let ((l (java:new "java.util.ArrayList")))
  (java:call l "add" (java:handle '(1 2) "pair"))
  (list (java:call l "toString") (java:call l "get" 0)))
; => ("[pair]" (1 2))
```

```lisp
(let ((s (java:new "java.util.TreeSet")))
  (java:call s "add" (java:handle 'a "a" 0 "2"))
  (java:call s "add" (java:handle 'b "b" 0 "1"))
  (java:call s "toString"))
; => "[b, a]"
```

- A nil `hash` makes a handle equal only to a handle of the very same `value` (`eq`), its
  `hashCode` the value's identity hash. Its `text` may then be nil: its `toString` is
  `Object`'s spelling, the class and the hex hash (`my.Cell@1b6d3586`).
- `order` may be a function: `compareTo` calls it with the value and the Lisp value of the
  object compared with, and answers the sign of the real number it answers. A nil `order`
  makes the handle order nothing. A handle that does not order the object, or meets a handle
  of another `class`, throws the `ClassCastException` a cast would (`class S cannot be cast to
  class K`).
- `class` names the class Java's messages give the handle, and splits equality and order:
  handles of two classes are never `equals` and never compare.
- A handle of a real number is a `java.lang.Number` of it: `doubleValue` (a ratio's
  `DECIMAL64` quotient), `longValue`, `intValue`.

```lisp
(let ((s (java:new "java.util.TreeSet"))
      (by-value (lambda (r other) (if (realp other) (signum (- r other)) nil))))
  (dolist (r (list 1/2 1/3 3/4))
    (java:call s "add" (java:handle r (princ-to-string r) 0 by-value "my.Ratio")))
  (list (java:call s "toString") (java:call s "first")
        (java:call (java:handle 1/3 "1/3" 0 by-value "my.Ratio") "doubleValue")))
; => ("[1/3, 1/2, 3/4]" 1/3 0.3333333333333333)
```

A `text` that is no string (or nil with a hash), a `hash` that is no integer or nil, an
`order` that is no string, function or nil, or a `class` that is no string or nil is an
error: `java:handle expects (java:handle value text [hash [order ["class"]]]), got 2`. The
object is of `am.ik.rontolisp.runtime.RontoJavaHandle` (`RontoJavaNumberHandle` for a
number), a class the interpreter uses too and a compiled program ships beside it, made with
no reflection. See also [java:view](java-view.md).
