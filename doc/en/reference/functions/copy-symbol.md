# copy-symbol

`(copy-symbol symbol &optional copy-properties)`

Returns a fresh uninterned symbol with the same name as `symbol`.
`copy-properties` is accepted and ignored: there is no `(setf symbol-plist)` to
carry a property list across with.

The copy inherits [`make-symbol`](make-symbol.md)'s identity deviation exactly. A
symbol here IS its spelling and there is no intern table, so two uninterned
symbols of one name are `eq` -- a copy is not distinguishable from any other copy
of the same symbol. Code that only needs a name nobody else uses should reach for
[`gensym`](gensym.md), which does give a fresh one every call.

```lisp
(symbol-name (copy-symbol 'foo)) ; => "FOO"
```

## Backend support

Works on all four backends: one definition in rontolisp source over
`make-symbol`.
