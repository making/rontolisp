# char-name

`(char-name character)`

The name of a non-graphic character (`"Space"`, `"Newline"`, `"Tab"`, ...), a `"U+XXXX"` form for other non-printing code points, or nil for a graphic character.

```lisp
(char-name #\Space) ; => "Space"
```

```lisp
(char-name #\a) ; => nil
```
