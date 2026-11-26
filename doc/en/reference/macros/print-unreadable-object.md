# print-unreadable-object

`(print-unreadable-object (object stream &key type identity) body...)`

Writes `#<`...`>` around the body's output to `stream` and returns `nil`. A true `:type` prints the [`type-of`](../functions/type-of.md) designator first, followed by a space when a body follows it; `:identity` is accepted but prints no address -- there is no object-identity token in the value model, and a per-backend one would make the same program print differently on each backend. The usual body of a [`print-object`](../functions/print-object.md) method. Under `*print-readably*` it signals [`print-not-readable`](../functions/print-not-readable-object.md) for `object` instead, before the body runs.

The type designator is written like any symbol. Under `prin1`/`~S` it carries the package qualifier the current package needs: none where the name is accessible, `PKG:NAME` or `PKG::NAME` otherwise, `COMMON-LISP-USER::NAME` for a `cl-user` type printed from another package. Under `princ`/`~A` it is just `NAME`. `*print-case*` cases it in both.

```lisp
(with-output-to-string (s)
  (print-unreadable-object ('x s :type nil)
    (princ "thing" s))) ; => "#<thing>"
```
