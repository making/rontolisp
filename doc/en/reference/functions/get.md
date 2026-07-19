# get

`(get symbol indicator &optional default)`

Reads the symbol's property list entry for `indicator`, or `default` when absent; `(setf (get symbol indicator) value)` writes it. Symbols have no identity cells to hang plists on (they compare by name), so the store is one program-global name-keyed table.

```lisp
(setf (get 'my-sym 'color) :red)
(get 'my-sym 'color) ; => :red
```

```lisp
(get 'my-sym 'absent :fallback) ; => :fallback
```
