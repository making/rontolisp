# symbol-plist

`(symbol-plist symbol)`

Returns the symbol's whole property list — the indicator/value pairs [`get`](get.md) indexes into — or `nil` when it has none. Symbols have no identity cells to hang plists on (they compare by name), so the list comes out of the same program-global name-keyed store `get` writes to. There is no `(setf symbol-plist)`.

```lisp
(symbol-plist 'no-props) ; => NIL
```

```lisp
(setf (get 'my-sym 'color) :red)
(symbol-plist 'my-sym) ; => (COLOR :RED)
```
