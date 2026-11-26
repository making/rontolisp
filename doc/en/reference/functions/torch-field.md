# torch:field

`(torch:field module name)`

Returns the value of the module's named field, `name` being the field's keyword: a parameter, a buffer, a submodule, a list of submodules or a plain hyper-parameter. Signals when the module has no such field, so a misspelled name is loud rather than silently `NIL`. This is how a layer's forward reads its own parameters (see [`torch:module`](torch-module.md)).

```lisp
(torch:shape (torch:field (torch:linear 3 2) :weight))  ; => (3 2)
(torch:shape (torch:field (torch:linear 3 2) :bias))    ; => (2)
```
