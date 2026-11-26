# prin1-to-string

`(prin1-to-string object)`

Returns, as a string, the text that `prin1` would write for `object` -- the readable form in which strings keep their surrounding quotes (with every embedded `"` and `\` preceded by a `\`) and characters use `#\` syntax. Nothing is printed; the rendering is captured and returned, so `(read-from-string (prin1-to-string s))` gives `s` back.

```lisp
(prin1-to-string "abc") ; => "\"abc\""
```
