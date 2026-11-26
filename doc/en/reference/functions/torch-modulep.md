# torch:modulep

`(torch:modulep x)`

Returns `T` when x is a `torch` module (the fixed-layout record [`torch:module`](torch-module.md) builds), `NIL` otherwise. A tensor is not a module.

```lisp
(torch:modulep (torch:linear 2 2))  ; => T
(torch:modulep (torch:tensor 1.0))  ; => NIL
```
