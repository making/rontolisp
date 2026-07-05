# symbol-name

`(symbol-name symbol)`

Returns the symbol's name as a string. rontolisp symbols are case-preserving (a name reads back exactly as written, normally lowercase), so unlike Common Lisp the result is **not** upcased: `(symbol-name 'foo)` is `"foo"`, not `"FOO"`. The stored name is returned verbatim — the same text `princ` prints — so a keyword keeps its leading `:` and a [`gensym`](gensym.md)/[`make-symbol`](make-symbol.md) result keeps its `#:` prefix.

On the compiled backends (JVM/WASM) `symbol-name` shares the `princ-to-string` machinery, so a non-symbol argument yields its display text instead of signaling an error (the interpreter signals).

```lisp
(symbol-name 'foo) ; => "foo"
```

```lisp
(symbol-name :bar) ; => ":bar"
```

```lisp
(intern (symbol-name 'round-trip)) ; => round-trip
```
