# princ

`(princ object &optional stream)`

Writes `object` to standard output in human-readable form, with no surrounding quotes on strings and no `#\` prefix on characters, and without a trailing newline. This is the form meant for display rather than for reading back. With the optional stream argument the output goes to that stream instead of standard output. Returns `object`.

```lisp
(princ "hello")
```

```
hello
```
