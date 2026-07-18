# symbol-name

`(symbol-name symbol)`

Returns the symbol's name as a string. rontolisp symbols are case-preserving (a name reads back exactly as written, normally lowercase), so unlike Common Lisp the result is **not** upcased: `(symbol-name 'foo)` is `"foo"`, not `"FOO"`. A keyword's leading `:` and a [`gensym`](gensym.md)/[`make-symbol`](make-symbol.md) result's `#:` prefix are package markers, not part of the name, and are stripped — the same text `princ` prints (`prin1` keeps the markers).

On the compiled backends (JVM/WASM) `symbol-name` shares the `princ-to-string` machinery, so a non-symbol argument yields its display text instead of signaling an error (the interpreter signals).

```lisp
(symbol-name 'foo) ; => "foo"
```

```lisp
(symbol-name :bar) ; => "bar"
```

```lisp
(intern (symbol-name 'round-trip)) ; => round-trip
```
