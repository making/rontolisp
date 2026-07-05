# make-symbol

`(make-symbol string)`

Returns a fresh uninterned symbol named `#:<string>` — the same `#:` convention [`gensym`](gensym.md) uses. rontolisp has no intern table (symbols compare by name), so "uninterned" is represented by the `#:` name prefix: the result is never `eq` to a plain symbol with the same spelling, but two `make-symbol` calls with the same string DO yield `eq` symbols (a deviation from Common Lisp, where every `make-symbol` result is a distinct object). For guaranteed-unique temporaries use `gensym`, which appends a counter.

```lisp
(make-symbol "temp") ; => #:temp
```

```lisp
(eq (make-symbol "foo") 'foo) ; => nil
```

```lisp
(symbolp (make-symbol "temp")) ; => t
```
