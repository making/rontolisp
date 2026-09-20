# file-string-length

`(file-string-length stream object)`

How far writing the object -- a character or a string -- would move [file-position](file-position.md) on the stream: its UTF-8 byte length, since UTF-8 is the one external format every backend writes.

```lisp
(with-output-to-string (s) (princ (list (file-string-length s #\a)
                                        (file-string-length s "abc"))
                                  s))
; => "(1 3)"
```
