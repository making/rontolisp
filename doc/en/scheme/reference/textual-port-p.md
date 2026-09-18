# textual-port?

`(textual-port? obj)`

Returns `#t` if `obj` is a textual port: a string port or one of the standard ports. A port is textual or binary, never both (Gauche's ports are both).

```scheme
(textual-port? (open-input-string "x")) ; => #t
```
