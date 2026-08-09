# print-unreadable-object

`(print-unreadable-object (object stream &key type identity) body...)`

Writes `#<`...`>` around the body's output to `stream` and returns `nil`. A true `:type` prints the [`type-of`](../functions/type-of.md) designator first, followed by a space when a body follows it; `:identity` is accepted but prints no address -- there is no object-identity token in the value model, and a per-backend one would make the same program print differently on each backend. The usual body of a [`print-object`](../functions/print-object.md) method.

The type designator is written like any symbol, so `*print-escape*` decides whether its package qualifier appears: under `prin1`/`~S` a type defined in another package prints as `PKG:NAME`, under `princ`/`~A` as just `NAME`. The two agree for a type whose name needs no qualifier.

```lisp
(with-output-to-string (s)
  (print-unreadable-object ('x s :type nil)
    (princ "thing" s))) ; => "#<thing>"
```
