# torch:module-kind

`(torch:module-kind module)`

Returns the module's kind keyword -- the one given to [`torch:module`](torch-module.md), `:linear` / `:embedding` / `:sequential` / `:layer-norm` / `:dropout` for the built-in layers.

```lisp
(torch:module-kind (torch:linear 2 2))  ; => :LINEAR
(torch:module-kind (torch:sequential))  ; => :SEQUENTIAL
```
