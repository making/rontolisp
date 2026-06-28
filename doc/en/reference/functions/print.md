# print

`(print object)`

Writes `object` to standard output in its readable (`prin1`) form -- strings are surrounded by quotes and characters use `#\` syntax -- followed by a trailing newline, then returns `object`. Use it for quick, machine-readable output that another `read` could parse back.

```lisp
(print "hello")
```

```
"hello"
```
