# print-object

`(print-object object stream)`

The generic function the printer consults. Defining a [`defmethod`](../special-forms/defmethod.md) on it for a [`defclass`](../special-forms/defclass.md) class or a [`defstruct`](../special-forms/defstruct.md) type makes `print`, `princ`, `prin1`, `princ-to-string`, `prin1-to-string` and [`format`](../macros/format.md)'s `~A`/`~S` render instances of that type through the method instead of the built-in `#S(...)` / `#<...>` syntax. The method writes to the stream it is given and its return value is ignored; [`print-unreadable-object`](../macros/print-unreadable-object.md) is the usual body.

The PRINTER only routes through the generic for a type some method specializes on: a type no method covers keeps the built-in rendering, and a program that defines no `print-object` method prints exactly as it did before.

A DIRECT `(print-object object stream)` call is a different matter and always works, with or without a user method on the object -- Common Lisp supplies a system method for every object, and so does rontolisp. It writes the object's raw rendering (`*print-escape*` picking the `prin1` or the `princ` spelling) and returns the object, so defining a method on one class never loses the printer for the rest, and `(call-next-method)` out of the least specific method reaches it.

```lisp
(with-output-to-string (s) (print-object 42 s)) ; => "42"
```

One limit on the direct call: the system method renders raw, so a nested instance inside the value it is handed does not get its own method consulted. Printing through `print`/`princ`/`prin1` does walk into it, as below.

A [`defstruct`](../special-forms/defstruct.md) `(:print-object fn)` / `(:print-function fn)` option is exactly a method on this generic, so the two are interchangeable and a later `defmethod` on the same type replaces the option's method.

The one built-in rendering that is not `#S(...)`/`#<...>` is a CONDITION's: `princ`/`princ-to-string`/`~A` write its [`:report`](../macros/define-condition.md) instead. A `print-object` method on a condition class wins over that report, in both escape modes.

`*print-escape*` is bound around the call — `t` for `prin1`/`print`/`~S`, `nil` for `princ`/`~A` — so a portable method that branches on it (the Common Lisp idiom for rendering readably or bare) behaves the same way here. `*print-readably*` is always `nil`.

```lisp
(defstruct po-uri text)
(defmethod print-object ((u po-uri) stream)
  (if (and (null *print-readably*) (null *print-escape*))
      (write-string (po-uri-text u) stream)
      (format stream "#<URI ~A>" (po-uri-text u))))
(list (princ-to-string (make-po-uri :text "/x")) (prin1-to-string (make-po-uri :text "/x")))
; => ("/x" "#<URI /x>")
```

The method is consulted wherever the instance SITS, not only when the printing operator is handed it directly: an element of a printed list or vector — at any depth, and in a dotted tail — goes through the method too.

```lisp
(defstruct po-node value)
(defmethod print-object ((n po-node) stream)
  (print-unreadable-object (n stream :type t)
    (princ (po-node-value n) stream)))
(list (princ-to-string (make-po-node :value 42))
      (princ-to-string (list (make-po-node :value 7) (vector (make-po-node :value 8)))))
; => ("#<PO-NODE 42>" "(#<PO-NODE 7> #(#<PO-NODE 8>))")
```

Lite: the containers walked that way are the list and the general one-dimensional vector. A value stored in a STRUCTURE or class slot, a hash table, an array of rank other than one or a specialized float vector is still rendered by the container's own printer, so a method on its type does not apply there.
