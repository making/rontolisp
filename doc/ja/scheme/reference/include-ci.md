# include-ci

`(include-ci "file"...)`

[include](include.md) と同じですが、各ファイルを `#!fold-case` で始まっているかのように読みます: 識別子と文字名は小文字に畳み込まれ、文字列はそのままです。

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
