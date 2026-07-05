# string

`(string x)`

Coerces a *string designator* to a string. A string is returned unchanged, a symbol yields its name, and a character yields a one-character string. `t` and `nil` coerce like symbols (`"t"` / `"nil"`). Because rontolisp symbol names are case-preserving, the result is not upcased (unlike Common Lisp).

On the compiled backends (JVM/WASM) `string` shares the `princ-to-string` machinery, so a non-designator argument yields its display text instead of signaling an error (the interpreter signals).

```lisp
(string 'foo) ; => "foo"
```

```lisp
(string #\a) ; => "a"
```

```lisp
(string "already") ; => "already"
```
