# write-line

`(write-line string &optional stream &key start end)`

Writes the given string followed by a newline, and returns the string. With no stream argument it writes to standard output; given an output stream it writes there instead -- a file stream opened by `open` or `with-open-file`, a socket, or a `with-output-to-string` string stream. Works in all three backends. Unlike `print`/`prin1`, it writes the raw string contents without surrounding quotes. The `:start`/`:end` keywords bound the written substring (a `nil` `:end` means the string's length); the full string is still the return value. A CLOS instance extending rontolisp's Gray output-stream base class also works as the stream -- the bounds reach `rontolisp:stream-write-string`'s own `start`/`end`.

```console
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out)
  (write-line "world" out))
```

This writes two lines, `hello` and `world`, into `greeting.txt`. Each call appends its own trailing newline and returns the string it wrote.

```lisp
(string= (with-output-to-string (s)
           (write-line "hello" s :start 1 :end 3))
         (format nil "el~%")) ; => T
```

`write-line` still returns the whole string `"hello"`; only the written bytes are bounded.
