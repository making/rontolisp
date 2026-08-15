# synonym-stream-symbol

`(synonym-stream-symbol stream)`

Returns the symbol a synonym stream forwards to, i.e. the argument [`make-synonym-stream`](make-synonym-stream.md) was given. Signals when `stream` is not a synonym stream.

```lisp
(synonym-stream-symbol (make-synonym-stream '*standard-output*)) ; => *STANDARD-OUTPUT*
```
