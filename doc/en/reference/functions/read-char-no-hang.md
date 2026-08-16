# read-char-no-hang

`(read-char-no-hang &optional stream eof-error-p eof-value)`

Reads one character from `stream` (default: standard input) if one is available without waiting, and returns it. On a stream HANDLE -- a file, a string input stream, a socket -- rontolisp answers exactly what `read-char` answers: no source it can open reports "a character would block" separately from "read one", and CL allows an implementation to say so. On a [Gray stream](../../guides/gray-streams.md) instance it dispatches to `rontolisp:stream-read-char-no-hang`, which is the generic a class with a genuinely non-blocking source overrides; that generic's own default is `stream-read-char`. At end of input it signals an `end-of-file` condition unless `eof-error-p` is `nil`, in which case it returns `eof-value` (default `nil`).

```lisp
(with-input-from-string (s "hi")
  (list (read-char-no-hang s)
        (read-char-no-hang s)
        (read-char-no-hang s nil :end))) ; => (#\h #\i :END)
```
