# print-unreadable-object

`(print-unreadable-object (object stream &key type identity) body...)`

Writes `#<`...`>` around the body's output to `stream` and returns `nil`. A true `:type` prints the `class-of` designator (plus a space) first; `:identity` is accepted but not printed (there is no printable address). Used by libraries' `print-object` methods.

```lisp
(with-output-to-string (s)
  (print-unreadable-object ('x s :type nil)
    (princ "thing" s))) ; => "#<thing>"
```
