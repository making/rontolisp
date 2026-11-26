# torch:parameters

`(torch:parameters module)`

Returns every parameter reachable from the module, in registration order and deduplicated by identity: its own parameter fields, then those of its submodules and of any **list** of submodules it holds, recursively. A weight shared by two layers appears once. This is the list a training loop (and an optimizer) is built over -- reaching a parameter needs no declaration beyond putting it in the fields plist.

```lisp
(defparameter *net*
  (torch:sequential (torch:linear 4 8) (function torch:relu) (torch:linear 8 2)))
(length (torch:parameters *net*))                       ; => 4
(torch:shape (car (torch:parameters *net*)))            ; => (4 8)
```
