# prin1

`(prin1 object &optional stream)`

Writes `object` to standard output in its readable form, exactly like `print` but without the trailing newline. Strings are printed with surrounding quotes and characters in `#\` syntax, so the output could be read back by `read`. Inside a string every embedded `"` and `\` is preceded by a `\`; a newline is printed literally. Binding `*print-case*` converts the case of every symbol printed ([Reader Case](../../guides/reader-case.md)); `*print-length*` / `*print-level*` truncate lists and vectors (`(1 2 ...)`, `#`), `*print-gensym*` `nil` drops a gensym's `#:`, and `*print-base*` / `*print-radix*` re-spell integers and ratios (`FF`, `#xFF`, `255.`). A symbol's package qualifier is printed only when the symbol is not accessible in the current `*package*` ([Packages](../packages.md)). With the optional stream argument the output goes to that stream instead of standard output. Returns `object`.

```lisp
(prin1 "hello")
```

```
"hello"
```

```lisp
(prin1 "{\"hello\":\"aaa\"}")
```

```
"{\"hello\":\"aaa\"}"
```
