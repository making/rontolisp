# write-line

`(write-line string &optional stream)`

Writes the given string followed by a newline, and returns the string. With no stream argument it writes to standard output; given an output stream opened by `open` or `with-open-file` it writes to that file. Works in all three backends. Unlike `print`/`prin1`, it writes the raw string contents without surrounding quotes.

```console
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out)
  (write-line "world" out))
```

This writes two lines, `hello` and `world`, into `greeting.txt`. Each call appends its own trailing newline and returns the string it wrote.
