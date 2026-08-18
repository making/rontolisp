# string

`(string x)`

Coerces a *string designator* to a string. A string is returned unchanged, a symbol yields its [`symbol-name`](symbol-name.md) (a keyword's leading `:` and a gensym's `#:` are package markers and are stripped), and a character yields a one-character string. `t` and `nil` coerce like symbols (`"T"` / `"NIL"`). Symbols read upcased like Common Lisp, so `(string 'foo)` is `"FOO"` and `(string 'car)` is `"CAR"`.

A non-designator argument signals an error on every backend. `string` is the single coercion every string-designator position routes through -- the [`string-trim`](string-trim.md) family's trimmed value, the case operators, [`string=`](string-eq.md) and the ordering predicates -- so anything it accepted silently would become a wrong answer there instead of a type error.

```lisp
(string 'foo) ; => "FOO"
```

```lisp
(string #\a) ; => "a"
```

```lisp
(string "already") ; => "already"
```
