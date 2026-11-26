# get-macro-character

`(get-macro-character char &optional (readtable *readtable*))`

The reader macro function of `char` and, as the second value, whether it is non-terminating; `nil` for a character that is not a macro character. `nil` as `readtable` designates the standard readtable. A standard macro character answers its standard function, the same object in every readtable, so it can be installed under another character. Called, a standard function reads its datum by the standard syntax for its character, whatever the current readtable installed there, so a reader macro function can fall back to the standard one it replaced. Works on all four backends.

```lisp
(list (functionp (get-macro-character #\()) (get-macro-character #\a)) ; => (T NIL)
```
