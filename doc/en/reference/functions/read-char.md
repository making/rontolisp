# read-char

`(read-char &optional stream eof-error-p eof-value)`

Reads one character from `stream` (default: standard input) and returns it. The stream may be a file stream opened by `open`/`with-open-file` or a string input stream from `with-input-from-string`. At end of input it signals an `end-of-file` condition unless `eof-error-p` is `nil`, in which case it returns `eof-value` (default `nil`). Because the condition is the registered `end-of-file` class, the usual CL lexer shape -- a read loop wrapped in `(handler-case ... (end-of-file (e) ...))` -- terminates as written. On the interpreter and the JVM backend a character is a UTF-16 code unit, matching the rest of the string representation; on the WASM backend strings are byte-indexed (like `char`/`schar`), so a character read is a byte read.

```lisp
(with-input-from-string (s "hi")
  (let* ((c1 (read-char s))
         (c2 (read-char s))
         (c3 (read-char s nil :end)))
    (list c1 c2 c3))) ; => (#\h #\i :END)
```
