# prin1

`(prin1 object &optional stream)`

Writes `object` to standard output in its readable form, exactly like `print` but without the trailing newline. Strings are printed with surrounding quotes and characters in `#\` syntax, so the output could be read back by `read`. Inside a string every embedded `"` and `\` is preceded by a `\`; a newline is printed literally. With the optional stream argument the output goes to that stream instead of standard output. Returns `object`.

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
