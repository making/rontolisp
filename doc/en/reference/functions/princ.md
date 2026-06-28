# princ

`(princ object)`

Writes `object` to standard output in human-readable form, with no surrounding quotes on strings and no `#\` prefix on characters, and without a trailing newline. This is the form meant for display rather than for reading back. Returns `object`.

```lisp
(princ "hello")
```

```
hello
```
