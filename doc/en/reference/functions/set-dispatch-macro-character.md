# set-dispatch-macro-character

`(set-dispatch-macro-character disp-char sub-char function &optional readtable)`

Lite stub: accepted and ignored, returning `t` — user dispatch macros cannot extend the Java-side reader.

Two dispatch syntaxes that libraries define this way are built into the reader instead, so those libraries work unchanged: `#N@(...)` (ironclad's s-box literal, read as a vector of the given element width) and `#L(...)` (iterate's numbered-argument lambda: `#L(list !2 !3)` reads as `#'(lambda (!1 !2 !3) (list !2 !3))`, the arity being the highest `!n` the body mentions unless `#nL` spells it out). Any OTHER dispatch character stays unhandled, and its literals reach the ordinary reader.

```lisp
(set-dispatch-macro-character #\# #\7 (lambda (s c n) nil)) ; => T
```
