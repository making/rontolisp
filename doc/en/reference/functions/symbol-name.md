# symbol-name

`(symbol-name symbol)`

Returns the symbol's name as a string. The reader upcases unescaped symbols like Common Lisp (see the [reader case guide](../../guides/reader-case.md)), so a symbol reports its upcased name — `(symbol-name 'foo)` is `"FOO"` and `(symbol-name 'car)` is `"CAR"`, the Common Lisp answer. A keyword's leading `:` and a [`gensym`](gensym.md)/[`make-symbol`](make-symbol.md) result's `#:` prefix are package markers, not part of the name, and are stripped — the same text `princ` prints (`prin1` keeps the markers).

On the compiled backends (JVM/WASM) `symbol-name` shares the `princ-to-string` machinery, so a non-symbol argument yields its display text instead of signaling an error (the interpreter signals).

The reader supports the Common Lisp escape syntaxes for symbol names: a backslash makes the next character part of the name verbatim, and a `|...|` multiple escape makes everything between the pipes part of the name — whitespace and terminating characters included — so `'|when used|` is one symbol named `"when used"` and `'|Foo|` keeps its mixed case.

```lisp
(symbol-name 'foo) ; => "FOO"
```

```lisp
(symbol-name :bar) ; => "BAR"
```

```lisp
(symbol-name 'car) ; => "CAR"
```

```lisp
(intern (symbol-name 'round-trip)) ; => ROUND-TRIP
```

```lisp
(symbol-name '|when used|) ; => "when used"
```
