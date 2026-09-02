# princ

`(princ object &optional stream)`

Writes `object` to standard output in human-readable form, with no surrounding quotes on strings and no `#\` prefix on characters, and without a trailing newline. This is the form meant for display rather than for reading back. A symbol prints as its [`symbol-name`](symbol-name.md) alone: a keyword's leading `:`, a gensym's `#:` and a package qualifier (`quri:uri` prints as `URI`) are all where the symbol lives rather than part of its name, and none of them is printed (`prin1`/`print` keep them). A condition object prints its [`:report`](../macros/define-condition.md) (`prin1` keeps the `#<...>` instance syntax). Binding `*print-case*` converts the case of every symbol printed ([Reader Case](../../guides/reader-case.md)); `*print-length*` / `*print-level*` truncate lists and vectors (`(1 2 ...)`, `#`), and `*print-base*` / `*print-radix*` re-spell integers and ratios. With the optional stream argument the output goes to that stream instead of standard output. Returns `object`.

```lisp
(princ "hello")
(princ :ready)
```

```
helloREADY
```
