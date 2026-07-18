# stream-element-type

`(stream-element-type stream)`

Always the symbol `character`: every rontolisp stream is a character stream (there are no binary element types).

```lisp
(with-input-from-string (s "x")
  (stream-element-type s)) ; => character
```
