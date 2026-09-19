# include-ci

`(include-ci "file"...)`

Like [include](include.md), but reads each file as if it began with `#!fold-case`: identifiers and character names are folded to lower case, strings are not.

```scheme
; file: loud.scm
(DEFINE (SHOUT X) (STRING-APPEND X "!"))
```

```scheme
(include-ci "loud.scm")
(display (shout "hey"))
(newline)
```

```
hey!
```
