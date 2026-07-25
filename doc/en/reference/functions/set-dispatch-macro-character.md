# set-dispatch-macro-character

`(set-dispatch-macro-character disp-char sub-char function &optional readtable)`

Lite stub: accepted and ignored, returning `t` — user dispatch macros cannot extend the Java-side reader.

```lisp
(set-dispatch-macro-character #\# #\7 (lambda (s c n) nil)) ; => T
```
