# write-string

`(write-string string &optional stream)`

Writes the raw string contents -- without surrounding quotes and without a trailing newline -- and returns the string: `write-line` minus the newline. With no stream argument it writes to standard output; given an output stream (a file stream or a `with-output-to-string` string stream) it writes there instead.

```lisp
(write-string "one, ")
(write-string "two")
```

```
one, two
```
