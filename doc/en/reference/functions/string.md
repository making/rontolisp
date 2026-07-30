# string

`(string x)`

Coerces a *string designator* to a string. A string is returned unchanged, a symbol yields its [`symbol-name`](symbol-name.md) (a keyword's leading `:` and a gensym's `#:` are package markers and are stripped), and a character yields a one-character string. `t` and `nil` coerce like symbols (`"T"` / `"NIL"`). Symbols read upcased like Common Lisp, so `(string 'foo)` is `"FOO"` and `(string 'car)` is `"CAR"`.

On the compiled backends (JVM/WASM) `string` shares the `princ-to-string` machinery, so a non-designator argument yields its display text instead of signaling an error (the interpreter signals).

```lisp
(string 'foo) ; => "FOO"
```

```lisp
(string #\a) ; => "a"
```

```lisp
(string "already") ; => "already"
```
