# read-line

`(read-line &optional stream)`

Reads one line of text and returns it as a string with the trailing newline removed. With no argument it reads from standard input; given a stream opened by `open` or `with-open-file` it reads the next line from that stream. At end of input it returns `nil` rather than signalling an error. Works in all three backends; unlike `read`, it returns the raw line without parsing it as an S-expression.

```console
(print (read-line))
```

Typing `hello world` on standard input makes `read-line` return the string `"hello world"`. When the input is exhausted it returns `nil`, which is the usual loop-termination test when reading a file line by line.
