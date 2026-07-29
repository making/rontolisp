# peek-char

`(peek-char &optional peek-type stream eof-error-p eof-value)`

Returns the next character of `stream` (default: standard input) **without consuming it**, so the following `read-char` returns the same character. `peek-type` selects what to skip first: `nil` (the default) skips nothing, `t` skips whitespace, and a character skips input up to that character. In every case the character that is returned is left in the stream. At end of input it signals an `end-of-file` condition unless `eof-error-p` is `nil`, in which case it returns `eof-value` (default `nil`).

```lisp
(with-input-from-string (s "  ab")
  (list (peek-char t s) (read-char s) (peek-char nil s) (read-char s))) ; => (#\a #\a #\b #\b)
```
