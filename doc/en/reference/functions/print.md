# print

`(print object &optional stream)`

Writes `object` to standard output in its readable (`prin1`) form -- strings are surrounded by quotes, with every embedded `"` and `\` preceded by a `\`, and characters use `#\` syntax -- followed by a trailing newline, then returns `object`. With the optional stream argument (a file stream or a `with-output-to-string` string stream) the output goes to that stream instead of standard output. Use it for quick, machine-readable output that another `read` could parse back.

```lisp
(print "hello")
```

```
"hello"
```
