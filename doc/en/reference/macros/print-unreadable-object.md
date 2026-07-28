# print-unreadable-object

`(print-unreadable-object (object stream &key type identity) body...)`

Writes `#<`...`>` around the body's output to `stream` and returns `nil`. A true `:type` prints the [`type-of`](../functions/type-of.md) designator first, followed by a space when a body follows it; `:identity` is accepted but prints no address -- there is no object-identity token in the value model, and a per-backend one would make the same program print differently on each backend. The usual body of a [`print-object`](../functions/print-object.md) method.

```lisp
(with-output-to-string (s)
  (print-unreadable-object ('x s :type nil)
    (princ "thing" s))) ; => "#<thing>"
```
