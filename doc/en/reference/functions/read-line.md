# read-line

`(read-line &optional stream eof-error-p eof-value recursive-p)`

Reads one line of text and returns it as a string with the trailing newline removed (a CRLF line ending also loses its carriage return, like Java's `BufferedReader.readLine` -- so CRLF-terminated input such as HTTP over a [`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) socket reads as plain lines). With no argument it reads from standard input; given a stream opened by `open` or `with-open-file` it reads the next line from that stream. At end of input it returns `eof-value` (default `nil`) rather than signalling: `eof-error-p` defaults to `nil` here, where CL defaults it to `t`, and a true one signals `end-of-file`. `recursive-p` is accepted and ignored: it only matters to a reader macro's recursive read. Works in all three backends; unlike `read`, it returns the raw line without parsing it as an S-expression.

```console
(print (read-line))
```

Typing `hello world` on standard input makes `read-line` return the string `"hello world"`. When the input is exhausted it returns `nil`, which is the usual loop-termination test when reading a file line by line.
