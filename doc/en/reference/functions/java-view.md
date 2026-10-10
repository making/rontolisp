# java:view

`(java:view value items shape &optional printer order class)`

Makes a read-only Java collection that stands for `value`: its elements are `items`
converted as `Object` arguments are, once, when it is made -- or, with `:bytes`, the
`byte[]` an octet vector is to Java. `shape` is a keyword:

| `shape` | Java object | `items` |
|---------|-------------|---------|
| `:list` | a `java.util.List` | a proper list or a vector |
| `:vector` | a `java.util.List` that is also `RandomAccess` and `Comparable` | a proper list or a vector |
| `:set` | a `java.util.Set`, a repeated element once | a proper list or a vector |
| `:map` | a `java.util.Map` | a hash table, or a plist |
| `:bytes` | none: the `byte[]` of the octets | an `(unsigned-byte 8)` vector |

Java reads it through those interfaces: `equals` and `hashCode` follow their contracts, and
every write throws `UnsupportedOperationException`. Its `toString` is what `(printer value)`
answers (the Java spelling without a printer). A `:vector` view orders by `order`, called with
the value and the Lisp value of the object compared with, as a
[`java:handle`](java-handle.md)'s order function is; without one it orders nothing.
`class` names the class Java's messages give the view. Wherever Java hands a view back,
`java:` answers `value`. Part of the JVM-only `java` interop package — available on the
interpreter and in JVM-compiled classes, not on the WASM backend. See the [Java interop
guide](../../guides/java-interop.md#views-javaview).

```lisp
(let* ((items (list 1 "two"))
       (v (java:view items items :list (lambda (x) (format nil "<~{~A~^ ~}>" x))))
       (l (java:new "java.util.ArrayList")))
  (java:call l "add" v)
  (list (java:call v "size") (java:call l "toString") (eq (java:call l "get" 0) items)))
; => (2 "[<1 two>]" T)
```

```lisp
(handler-case (java:static "java.util.Collections" "sort" (java:view 'v (list 3 1 2) :list))
  (error (e) (princ-to-string e)))
; => "error calling java.util.Collections.sort: java.lang.UnsupportedOperationException"
```

As an argument a view is a host object of its class, passed itself wherever its class fits
(`List`, `Collection`, `Iterable`, `Object`, ...). Where a Java array is expected, a `:list`
or `:vector` view converts to an array of its items -- but only after every way to pass it
whole, so a varargs method holds it as one element. A `:set` or `:map` view converts nowhere
else.

```lisp
(let ((v (java:view 'v (list 1 2) :list)))
  (list (java:static "java.util.Arrays" "toString" v)
        (java:call (java:static "java.util.Arrays" "asList" v) "size")))
; => ("[1, 2]" 1)
```

A `:bytes` view is no collection, and Java never holds it: wherever a `byte[]` fits -- a
`byte[]` parameter, an `Object`, `Cloneable` or `Serializable` one, an element of another
view -- Java is handed the `byte[]` of its octets, and nowhere else does the view convert.
On the interpreter that array is the vector's own storage, so what Java stores into it, then
or later, the vector holds. A compiled program's octet vector stores its octets after a
width, so Java is handed a copy, which the call writes back into the vector when it returns:
the vector holds what Java stored during the call, not what a Java object that kept the
array stores later. A vector handed twice to one call is one array to Java. Its `printer` and
`class` are not used. A `byte[]` comes back as an octet vector at a call ending in
[`:octets`](java-call.md).

```lisp
(let* ((b (make-array 4 :element-type '(unsigned-byte 8)))
       (v (java:view b b :bytes)))
  (java:call (java:new "java.util.Random" 42) "nextBytes" v)
  (list b (java:static "java.util.Arrays" "toString" v)))
; => (#(53 157 65 186) "[53, -99, 65, -70]")
```

A `shape` that is none of the five, `items` that are no sequence (a `:map`'s no hash table or
plist of even length, a `:bytes` view's no `(unsigned-byte 8)` vector), a `printer` that is
no function or nil, an `order` that is no function or nil (a function only for a `:vector`),
or a `class` that is no string or nil is an error: `java:view expects (java:view value items
:list|:vector|:set|:map|:bytes [printer [order ["class"]]]), got :TREE`; an item that converts
to no `Object` is `java:view: no Java value for X`. The object is of an `am.ik.rontolisp.runtime.RontoJava*View` class, which the
interpreter uses too and a compiled program ships beside it, made with no reflection.
