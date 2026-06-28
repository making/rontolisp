# princ-to-string

`(princ-to-string object)`

Returns, as a string, the text that `princ` would write for `object` -- the human-readable form with no quotes on strings and no `#\` prefix on characters. Nothing is printed; the rendering is captured and returned. Useful for building text out of arbitrary values.

```lisp
(princ-to-string '(1 "x")) ; => "(1 x)"
```
