# java:handle

`(java:handle value "text" &optional hash "order")`

Makes a Java object that stands for `value`, a Lisp value Java has no value of (a symbol, a
list, ...). Java sees `text` as the object's `toString`, and two handles of one text are
`equals`. The object's `hashCode` is `hash`'s low 32 bits (the text's `hashCode` without
one), and handles order by `order` (the text without one), so a handle keys a
`java.util.HashMap` and sorts in a `java.util.TreeSet`. Wherever Java hands the handle back
-- a method's result, an array's element, a callback's argument -- `java:` answers `value`.
Part of the JVM-only `java` interop package — available on the interpreter and in
JVM-compiled classes, not on the WASM backend. See the [Java interop
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

A `text` or `order` that is not a string, or a `hash` that is not an integer, is an error:
`java:handle expects (java:handle value "text" [hash ["order"]]), got 2`. A compiled
program makes a handle an object of a class generated beside it (`Prog$Handle.class`), with
no reflection.
