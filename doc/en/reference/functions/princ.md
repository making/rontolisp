# princ

`(princ object &optional stream)`

Writes `object` to standard output in human-readable form, with no surrounding quotes on strings and no `#\` prefix on characters, and without a trailing newline. This is the form meant for display rather than for reading back. A symbol prints as its [`symbol-name`](symbol-name.md): a keyword's leading `:` and a gensym's `#:` are package markers, not part of the name, and are not printed (`prin1`/`print` keep them). A condition object prints its [`:report`](../macros/define-condition.md) (`prin1` keeps the `#<...>` instance syntax). With the optional stream argument the output goes to that stream instead of standard output. Returns `object`.

```lisp
(princ "hello")
(princ :ready)
```

```
helloREADY
```
